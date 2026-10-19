package com.foremen.service.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-06-07 (Requirements 9.3, 10.2; design §OverdueSweepService): the periodic sweep that
 * re-derives tranche statuses across every {@link ScheduleStatus#PUBLISHED} schedule and fires the
 * {@code trancheOverdue} notification <b>exactly once</b> per tranche on its first crossing into
 * {@link TrancheStatus#OVERDUE}.
 *
 * <p><b>Why only PUBLISHED.</b> A {@code DRAFT} schedule is not yet client-visible and carries no
 * commitment; a {@code SETTLED} schedule has every tranche {@code PAID} and so can have no open
 * tranche go overdue. Only {@code PUBLISHED} schedules can hold a tranche that newly crosses its due
 * date, so the sweep scans exactly those via {@link PaymentScheduleDao#findByStatus(ScheduleStatus)}.
 *
 * <h2>Status derivation (R10.2)</h2>
 * Overdue is re-derived on read (the service views) and here on the periodic sweep. For each tranche
 * the sweep recomputes the materialized status cache through the pure {@link TrancheStatusDeriver}
 * — the only writer of a status (R10.3) — and rolls the schedule to {@code SETTLED} iff every tranche
 * is {@code PAID} (R5.6). Nothing here mutates amounts or allocations; it only refreshes derived
 * state and marks first-crossings.
 *
 * <h2>One-shot overdue notification (R9.3)</h2>
 * The first time a tranche derives {@link TrancheStatus#OVERDUE} while its persisted
 * {@link PaymentTrancheEntity#getOverdueNotifiedAt()} marker is still {@code null}, the sweep stamps
 * the marker with {@code now} and publishes a {@link TrancheOverdueEvent}. Because the marker is
 * persisted, a later sweep over the same still-overdue tranche finds a non-null marker and does
 * <b>not</b> re-notify — there is no recurring reminder in this spec (R9.3). The emission itself is
 * the concern of the best-effort {@code PaymentNotificationEmitter} (task 5.6), wired as an
 * {@code AFTER_COMMIT} {@code @TransactionalEventListener} on the published event, so a notification
 * failure never rolls back the swept transition (R9.5). This service only publishes the event — it
 * never emits a notification directly, mirroring {@link PaymentTransactionService}'s
 * {@code OnlineFailedEvent} pattern.
 *
 * <h2>Scheduling</h2>
 * The sweep is driven by the {@code foremen.payments.overdue-sweep.cron} property (never hardcoded),
 * defaulting to the {@link Scheduled#CRON_DISABLED "-"} sentinel so the task is registered but never
 * fires until an operator supplies a real cron expression; a blank/absent schedule disables it
 * cleanly rather than failing cron parsing (mirroring {@code ImageReconciliationJob}). Spring
 * scheduling is already activated application-wide by {@code @EnableScheduling} on
 * {@link com.foremen.config.image.ImageStorageConfig}.
 */
@Slf4j
@Service
public class OverdueSweepService {

    private static final int SCALE = 2;

    private final PaymentScheduleDao paymentScheduleDao;
    private final TrancheStatusDeriver trancheStatusDeriver;
    private final ApplicationEventPublisher eventPublisher;

    public OverdueSweepService(PaymentScheduleDao paymentScheduleDao,
                               TrancheStatusDeriver trancheStatusDeriver,
                               ApplicationEventPublisher eventPublisher) {
        this.paymentScheduleDao = paymentScheduleDao;
        this.trancheStatusDeriver = trancheStatusDeriver;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The scheduled entry point: sweep every {@code PUBLISHED} schedule, re-deriving its tranche
     * statuses and firing the one-shot {@code trancheOverdue} event for each tranche that first
     * crosses into {@link TrancheStatus#OVERDUE} (Requirements 9.3, 10.2). Driven by
     * {@code foremen.payments.overdue-sweep.cron}; disabled by default via the {@code "-"} sentinel
     * until a cron is configured.
     */
    @Scheduled(cron = "${foremen.payments.overdue-sweep.cron:-}")
    @Transactional
    public void sweep() {
        List<PaymentScheduleEntity> published =
                paymentScheduleDao.findByStatus(ScheduleStatus.PUBLISHED);
        int firstCrossings = 0;
        for (PaymentScheduleEntity schedule : published) {
            firstCrossings += sweepSchedule(schedule);
        }
        if (firstCrossings > 0) {
            log.info("Overdue sweep fired {} first-crossing trancheOverdue event(s) across {} "
                    + "published schedule(s)", firstCrossings, published.size());
        }
    }

    /**
     * Re-derive one schedule's tranche statuses and publish a {@link TrancheOverdueEvent} for each
     * tranche that first crosses into {@code OVERDUE} (R9.3, R10.2). Invoked within the sweep's
     * transaction (opened by {@link #sweep()} through the scheduling proxy) so the status refresh +
     * marker stamp commit together and an {@code AFTER_COMMIT} emitter observes that commit. Returns
     * the number of first-crossings fired for this schedule.
     *
     * @param schedule the published schedule to sweep
     * @return the count of tranches that first crossed into {@code OVERDUE} on this sweep
     */
    private int sweepSchedule(PaymentScheduleEntity schedule) {
        Instant now = Instant.now();
        OffsetDateTime marker = now.atOffset(ZoneOffset.UTC);

        List<TrancheStatus> statuses = new ArrayList<>();
        List<TrancheOverdueEvent> firstCrossings = new ArrayList<>();

        for (PaymentTrancheEntity tranche : schedule.getTranches()) {
            TrancheStatus derived = trancheStatusDeriver.derive(
                    scale(tranche.getPaidGross()),
                    scale(tranche.getAmountGross()),
                    tranche.getDueDate(), now);
            tranche.setStatus(derived);
            statuses.add(derived);

            // R9.3: fire exactly once — only on the first crossing (marker still null) into OVERDUE.
            if (derived == TrancheStatus.OVERDUE && tranche.getOverdueNotifiedAt() == null) {
                tranche.setOverdueNotifiedAt(marker);
                firstCrossings.add(new TrancheOverdueEvent(tranche.getId(), schedule.getId()));
            }
        }

        // R5.6/R1.6: SETTLED iff every tranche is PAID; the deriver is the only status writer (R10.3).
        schedule.setStatus(trancheStatusDeriver.deriveSchedule(statuses, schedule.getStatus()));
        paymentScheduleDao.save(schedule);

        // Publish after the status + marker mutations are staged; the best-effort emitter (task 5.6)
        // consumes these AFTER_COMMIT so a notification failure never rolls back this sweep (R9.5).
        for (TrancheOverdueEvent event : firstCrossings) {
            eventPublisher.publishEvent(event);
        }
        return firstCrossings.size();
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
