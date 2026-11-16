package com.foremen.service.procurement;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.PurchaseRequestDao;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.RequestStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-11-00 (R13.2, R13.4): periodic sweep that fires the {@code notification.request.overdue}
 * event <b>exactly once</b> per purchase request.
 *
 * <p>Candidates come from {@link PurchaseRequestDao#findOverdueCandidates(LocalDate)} (expected date
 * before today, marker still null). Each is checked against the derived status
 * ({@link PurchaseRequestStatusDeriver}) and {@link PurchaseRequestOverdue}, so COMPLETED and
 * CANCELLED requests are skipped and never stamped. For an overdue request the sweep stamps
 * {@code overdueNotifiedAt} and, after staging, publishes a {@link PurchaseRequestOverdueEvent};
 * the AFTER_COMMIT emitter does the notification work. The marker is cleared elsewhere when the
 * expected date moves to today or later (R13.4), re-arming the one-shot.
 *
 * <p>Driven by {@code foremen.procurement.request-overdue-sweep.cron}; the {@code "-"} default
 * disables it (modelled on {@code OverdueSweepService}).
 */
@Slf4j
@Service
public class PurchaseRequestOverdueSweepService {

    private final PurchaseRequestDao purchaseRequestDao;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public PurchaseRequestOverdueSweepService(PurchaseRequestDao purchaseRequestDao,
                                              ApplicationEventPublisher eventPublisher,
                                              Clock clock) {
        this.purchaseRequestDao = purchaseRequestDao;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Scheduled(cron = "${foremen.procurement.request-overdue-sweep.cron:-}")
    @Transactional
    public void sweep() {
        PurchaseRequestOverdue overdue = new PurchaseRequestOverdue(clock);
        LocalDate today = overdue.today();
        Instant now = clock.instant();

        List<PurchaseRequestOverdueEvent> events = new ArrayList<>();
        for (PurchaseRequestEntity request : purchaseRequestDao.findOverdueCandidates(today)) {
            List<PurchaseRequestStatusDeriver.Stage> stages = request.getPositions().stream()
                    .map(p -> new PurchaseRequestStatusDeriver.Stage(p.getStatus(), p.isClientUnpaid()))
                    .toList();
            RequestStatus status = PurchaseRequestStatusDeriver.deriveStages(stages);
            if (!overdue.isOverdue(request.getExpectedDeliveryDate(), status)
                    || request.getOverdueNotifiedAt() != null) {
                continue;
            }
            request.setOverdueNotifiedAt(now);
            purchaseRequestDao.save(request);
            events.add(new PurchaseRequestOverdueEvent(request.getId(),
                    request.getProject().getId(),
                    request.getAssignedBuyer() == null ? null : request.getAssignedBuyer().getId()));
        }

        // Publish after all markers are staged; the AFTER_COMMIT emitter never rolls this back.
        events.forEach(eventPublisher::publishEvent);
        if (!events.isEmpty()) {
            log.info("Purchase request overdue sweep fired {} event(s)", events.size());
        }
    }
}
