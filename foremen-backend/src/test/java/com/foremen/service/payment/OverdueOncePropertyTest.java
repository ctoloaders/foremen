package com.foremen.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.ProjectEntity;

import org.mockito.ArgumentCaptor;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import org.springframework.context.ApplicationEventPublisher;

/**
 * FOR-06-07 task 8.2 — <b>Property 8 "OverdueOnce"</b> for {@link OverdueSweepService}
 * (design §OverdueSweepService; Requirements 9.3, 10.2): the one-shot overdue emission guarantee.
 *
 * <p>A tranche that has crossed its due date unpaid derives {@link TrancheStatus#OVERDUE}. The sweep
 * must publish the {@link TrancheOverdueEvent} for it <b>exactly once</b> — on its first crossing,
 * when the persisted {@link PaymentTrancheEntity#getOverdueNotifiedAt()} marker is still {@code null}
 * — and must <b>never re-notify</b> on any later sweep over the same still-overdue tranche (the
 * persisted marker suppresses recurrence, R9.3). There is no recurring reminder in this spec.
 *
 * <p>This is a fast, context-free jqwik property: it wires the <b>real</b> {@link OverdueSweepService}
 * with the <b>real</b> pure {@link TrancheStatusDeriver} and mocks only the persistence boundary
 * ({@link PaymentScheduleDao}) and the {@link ApplicationEventPublisher}. For each generated schedule
 * the sweep is driven {@code sweeps} times in a row (with {@code sweeps} in {@code [2, 10]}); the
 * event publisher counts every {@link TrancheOverdueEvent} actually published across <b>all</b>
 * sweeps. The property asserts the total published count for a given tranche equals exactly 1 iff
 * that tranche is (and stays) overdue across the sweeps — never more, regardless of how many times
 * the sweep runs. ≥100 iterations.
 *
 * <p>The {@code save} mock is an identity (it returns the same managed entity), mirroring the way the
 * sweep mutates the in-memory aggregate and persists it; because the same entity instances are kept
 * across sweeps, the {@code overdueNotifiedAt} marker set on the first sweep is observed by the next
 * — exactly the DB-backed persistence the production code relies on.
 *
 * <p><b>Validates: Requirements 9.3, 10.2</b>
 */
@Tag("Feature: FOR-06-07-client-payments, Property 8 OverdueOnce")
class OverdueOncePropertyTest {

    private static final int SCALE = 2;
    private static final LocalDate TODAY = LocalDate.now();

    /**
     * A generated tranche spec: its full amount, the already-paid fraction, and how many days before
     * (positive) or after (negative) today its due date falls. A tranche that is overdue has a due
     * date strictly in the past and a paid amount below its full amount.
     */
    private record TrancheSpec(BigDecimal amount, BigDecimal paid, int dueOffsetDays) {

        boolean isOverdue() {
            // OVERDUE per TrancheStatusDeriver: not PAID (paid < amount) AND today is after dueDate.
            return paid.compareTo(amount) < 0 && TODAY.isAfter(TODAY.minusDays(dueOffsetDays));
        }
    }

    private OverdueSweepService newService(ApplicationEventPublisher publisher,
                                           PaymentScheduleEntity schedule) {
        PaymentScheduleDao scheduleDao = mock(PaymentScheduleDao.class);
        when(scheduleDao.findByStatus(ScheduleStatus.PUBLISHED)).thenReturn(List.of(schedule));
        // Identity save: the sweep mutates + persists the same managed aggregate, so the
        // overdueNotifiedAt marker stamped on the first sweep is visible to every later sweep —
        // exactly the DB-backed one-shot suppression the production code depends on (R9.3).
        when(scheduleDao.save(any(PaymentScheduleEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        return new OverdueSweepService(scheduleDao, new TrancheStatusDeriver(), publisher);
    }

    /** A tranche amount in [0.02, 100000] to 2 decimals. */
    private Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.02"), new BigDecimal("100000"))
                .ofScale(SCALE);
    }

    /** A single tranche spec with an arbitrary paid fraction in [0, amount] and a due offset. */
    private Arbitrary<TrancheSpec> oneTrancheSpec() {
        // dueOffsetDays > 0 => due date in the past (overdue candidate); <= 0 => today/future.
        Arbitrary<Integer> dueOffset = Arbitraries.integers().between(-30, 60);
        return amounts().flatMap(amount ->
                Arbitraries.bigDecimals()
                        .between(BigDecimal.ZERO, amount)
                        .ofScale(SCALE)
                        .flatMap(paid -> dueOffset.map(off -> new TrancheSpec(amount, paid, off))));
    }

    /** A non-empty list (1..6) of tranche specs — the full schedule under sweep. */
    @Provide
    Arbitrary<List<TrancheSpec>> trancheSpecs() {
        return oneTrancheSpec().list().ofMinSize(1).ofMaxSize(6);
    }

    private PaymentScheduleEntity buildSchedule(List<TrancheSpec> specs) {
        PaymentScheduleEntity schedule = new PaymentScheduleEntity();
        schedule.setId(1L);
        ProjectEntity project = new ProjectEntity();
        project.setId(42L);
        schedule.setProject(project);
        schedule.setStatus(ScheduleStatus.PUBLISHED);

        List<PaymentTrancheEntity> tranches = new ArrayList<>();
        long id = 1L;
        int seq = 1;
        for (TrancheSpec spec : specs) {
            PaymentTrancheEntity tranche = new PaymentTrancheEntity();
            tranche.setId(id++);
            tranche.setSchedule(schedule);
            tranche.setSeqNo(seq++);
            tranche.setDueDate(TODAY.minusDays(spec.dueOffsetDays()));
            tranche.setAmountGross(spec.amount());
            tranche.setPaidGross(spec.paid());
            tranche.setStatus(TrancheStatus.PENDING);
            tranche.setOverdueNotifiedAt(null);
            tranches.add(tranche);
        }
        schedule.setTranches(tranches);
        return schedule;
    }

    // Feature: FOR-06-07-client-payments, Property 8 OverdueOnce
    // Across any number of repeated sweeps (>= 2), each overdue tranche emits TrancheOverdueEvent
    // exactly once (first crossing only, guarded by overdueNotifiedAt) and a non-overdue tranche
    // never emits; the total emission count never exceeds the number of overdue tranches (R9.3, 10.2).
    // Validates: Requirements 9.3, 10.2
    @Property(tries = 200)
    void overdueTrancheEmitsExactlyOnceAcrossRepeatedSweeps(
            @ForAll("trancheSpecs") List<TrancheSpec> specs,
            @ForAll @IntRange(min = 2, max = 10) int sweeps) {

        PaymentScheduleEntity schedule = buildSchedule(specs);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        OverdueSweepService service = newService(publisher, schedule);

        // Drive the sweep repeatedly — the marker set on the first crossing must suppress the rest.
        for (int i = 0; i < sweeps; i++) {
            service.sweep();
        }

        // Capture every object published to the (mocked) publisher across ALL sweeps and tally the
        // TrancheOverdueEvent emissions per tranche id. ApplicationEventPublisher#publishEvent is
        // overloaded (Object / ApplicationEvent); TrancheOverdueEvent is a plain record, so it is
        // routed through publishEvent(Object) — captured here with an Object captor.
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, atLeast(0)).publishEvent(captor.capture());
        java.util.Map<Long, Integer> emissionsByTranche = new java.util.HashMap<>();
        for (Object event : captor.getAllValues()) {
            if (event instanceof TrancheOverdueEvent overdue) {
                emissionsByTranche.merge(overdue.trancheId(), 1, Integer::sum);
            }
        }

        long expectedOverdueCount = specs.stream().filter(TrancheSpec::isOverdue).count();

        // Total emissions across ALL sweeps == number of overdue tranches (each emitted exactly once).
        int totalEmissions = emissionsByTranche.values().stream().mapToInt(Integer::intValue).sum();
        assertThat((long) totalEmissions)
                .as("total trancheOverdue emissions across %d sweeps must equal the number of overdue "
                        + "tranches (each emitted exactly once on first crossing, R9.3)", sweeps)
                .isEqualTo(expectedOverdueCount);

        // No single tranche is ever notified more than once — the one-shot guarantee per tranche.
        assertThat(emissionsByTranche.values())
                .as("no tranche may emit trancheOverdue more than once, no matter how many sweeps run")
                .allMatch(count -> count == 1);

        // Every overdue tranche carries a stamped marker; no non-overdue tranche does.
        for (int i = 0; i < specs.size(); i++) {
            PaymentTrancheEntity tranche = schedule.getTranches().get(i);
            boolean overdue = specs.get(i).isOverdue();
            if (overdue) {
                assertThat(tranche.getOverdueNotifiedAt())
                        .as("an overdue tranche must have its overdueNotifiedAt marker stamped (R9.3)")
                        .isNotNull();
                assertThat(emissionsByTranche.getOrDefault(tranche.getId(), 0))
                        .as("an overdue tranche emits exactly once").isEqualTo(1);
            } else {
                assertThat(tranche.getOverdueNotifiedAt())
                        .as("a non-overdue tranche must never be marked / notified").isNull();
                assertThat(emissionsByTranche.getOrDefault(tranche.getId(), 0))
                        .as("a non-overdue tranche never emits").isZero();
            }
        }
    }
}
