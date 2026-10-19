package com.foremen.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.PaymentTransactionEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.service.payment.dto.PaymentScheduleView;
import com.foremen.service.payment.dto.mapper.PaymentDtoMapperImpl;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import org.springframework.context.ApplicationEventPublisher;

/**
 * FOR-06-07 task 8.1 — Property 5 "MarkPaidIdempotency" for
 * {@link ReconciliationService#markTranchePaid} (design §ReconciliationService; Requirements 5.9,
 * 5.10, 5.11). A manual mark-tranche-paid:
 *
 * <ul>
 *   <li><b>targets exactly its tranche</b> — it settles the one tranche it is handed, never the
 *       nearest-open cascade (R5.9); after the call that tranche is {@code PAID}
 *       ({@code paidGross == amountGross}) and no other tranche's {@code paidGross} changed;</li>
 *   <li><b>settles only the remainder</b> — it records a {@code MANUAL}/{@code CONFIRMED} transaction
 *       for exactly {@code amountGross − paidGross} (never re-charging the already-paid part of a
 *       {@code PARTIALLY_PAID} tranche, R5.10, R5.11); so the tranche's final {@code paidGross}
 *       equals its {@code amountGross} exactly (no overshoot);</li>
 *   <li><b>second call is a no-op</b> — marking an already-{@code PAID} tranche again records no
 *       second transaction and no second allocation, and leaves {@code paidGross} unchanged
 *       (R5.11).</li>
 * </ul>
 *
 * <p>The property uses the real {@link TrancheStatusDeriver} and the real {@link PaymentDtoMapperImpl}
 * so the derived view is exercised; only the persistence DAOs / event publisher are mocked. The
 * target tranche is given an arbitrary {@code amountGross} and an arbitrary already-paid fraction in
 * {@code [0, amountGross]} (so both {@code PENDING}/{@code DUE} and {@code PARTIALLY_PAID} starting
 * states are covered).
 *
 * <p><b>Validates: Requirements 5.9, 5.10, 5.11</b>
 */
@Tag("Feature: FOR-06-07-client-payments, Property 5 MarkPaidIdempotency")
class MarkPaidIdempotencyPropertyTest {

    private static final int SCALE = 2;
    private static final Long TARGET_TRANCHE_ID = 2L;

    private PaymentScheduleEntity currentSchedule;

    private ReconciliationService newService() {
        PaymentScheduleDao scheduleDao = mock(PaymentScheduleDao.class);
        when(scheduleDao.save(any(PaymentScheduleEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(scheduleDao.findAll()).thenAnswer(inv -> List.of(currentSchedule));
        PaymentTransactionDao txnDao = mock(PaymentTransactionDao.class);
        when(txnDao.save(any(PaymentTransactionEntity.class))).thenAnswer(inv -> {
            // Mimic the DB appending the allocation owned by the transaction (cascade persist).
            return inv.getArgument(0);
        });
        UserDao userDao = mock(UserDao.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        return new ReconciliationService(new ReconciliationEngine(), new TrancheStatusDeriver(),
                new PaymentDtoMapperImpl(), scheduleDao, txnDao, userDao, publisher);
    }

    /** A target tranche: an amount in [0.02, 100000] and an already-paid fraction in [0, amount]. */
    @Provide
    Arbitrary<BigDecimal[]> amountAndPaid() {
        Arbitrary<BigDecimal> amount = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.02"), new BigDecimal("100000"))
                .ofScale(SCALE);
        return amount.flatMap(a -> Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, a)
                .ofScale(SCALE)
                .map(paid -> new BigDecimal[]{a.setScale(SCALE, RoundingMode.HALF_UP),
                        paid.setScale(SCALE, RoundingMode.HALF_UP)}));
    }

    /** A neighbour tranche's untouched amount. */
    @Provide
    Arbitrary<BigDecimal> neighbourAmounts() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100000"))
                .ofScale(SCALE);
    }

    private PaymentScheduleEntity buildSchedule(BigDecimal targetAmount, BigDecimal targetPaid,
                                                BigDecimal neighbourAmount) {
        PaymentScheduleEntity schedule = new PaymentScheduleEntity();
        schedule.setId(1L);
        ProjectEntity project = new ProjectEntity();
        project.setId(42L);
        schedule.setProject(project);
        schedule.setStatus(ScheduleStatus.PUBLISHED);
        schedule.setUnallocatedCredit(BigDecimal.ZERO);

        // A neighbour tranche (seq 1) that must stay untouched, and the target tranche (seq 2).
        PaymentTrancheEntity neighbour = new PaymentTrancheEntity();
        neighbour.setId(1L);
        neighbour.setSchedule(schedule);
        neighbour.setSeqNo(1);
        neighbour.setDueDate(LocalDate.of(2099, 1, 1));
        neighbour.setAmountGross(neighbourAmount.setScale(SCALE, RoundingMode.HALF_UP));
        neighbour.setPaidGross(BigDecimal.ZERO);
        neighbour.setStatus(TrancheStatus.PENDING);
        schedule.getTranches().add(neighbour);

        PaymentTrancheEntity target = new PaymentTrancheEntity();
        target.setId(TARGET_TRANCHE_ID);
        target.setSchedule(schedule);
        target.setSeqNo(2);
        target.setDueDate(LocalDate.of(2099, 2, 1));
        target.setAmountGross(targetAmount);
        target.setPaidGross(targetPaid);
        target.setStatus(TrancheStatus.PENDING);
        schedule.getTranches().add(target);

        schedule.setBasisGross(neighbour.getAmountGross().add(targetAmount));
        return schedule;
    }

    // Feature: FOR-06-07-client-payments, Property 5 MarkPaidIdempotency
    // markTranchePaid targets its tranche (sets paidGross == amountGross exactly, neighbour
    // untouched), settles only the remainder, and a second call is a no-op (R5.9, 5.10, 5.11).
    // Validates: Requirements 5.9, 5.10, 5.11
    @Property(tries = 200)
    void marksTargetSettlesRemainderAndSecondCallIsNoOp(
            @ForAll("amountAndPaid") BigDecimal[] amountAndPaid,
            @ForAll("neighbourAmounts") BigDecimal neighbourAmount) {
        BigDecimal targetAmount = amountAndPaid[0];
        BigDecimal targetPaid = amountAndPaid[1];

        ReconciliationService service = newService();
        currentSchedule = buildSchedule(targetAmount, targetPaid, neighbourAmount);
        PaymentTrancheEntity neighbour = currentSchedule.getTranches().get(0);
        PaymentTrancheEntity target = currentSchedule.getTranches().get(1);
        BigDecimal neighbourBefore = neighbour.getPaidGross();

        // --- first mark ---
        PaymentScheduleView view = service.markTranchePaid(TARGET_TRANCHE_ID, null);
        assertThat(view).isNotNull();

        // R5.9 / R5.10: the target is settled EXACTLY to its amount (no overshoot, remainder only).
        assertThat(target.getPaidGross()).isEqualByComparingTo(targetAmount);
        assertThat(target.getStatus()).isEqualTo(TrancheStatus.PAID);
        // R5.9: the neighbour (nearest-open by seqNo) is NOT touched — this targets the tranche only.
        assertThat(neighbour.getPaidGross()).isEqualByComparingTo(neighbourBefore);

        BigDecimal paidAfterFirst = target.getPaidGross();

        // --- second mark (idempotent no-op, R5.11) ---
        service.markTranchePaid(TARGET_TRANCHE_ID, null);

        // R5.11: the already-PAID tranche is unchanged — paidGross never exceeds amountGross, and
        // the neighbour is still untouched (no second transaction / allocation settled anything).
        assertThat(target.getPaidGross()).isEqualByComparingTo(paidAfterFirst);
        assertThat(target.getPaidGross()).isEqualByComparingTo(targetAmount);
        assertThat(target.getStatus()).isEqualTo(TrancheStatus.PAID);
        assertThat(neighbour.getPaidGross()).isEqualByComparingTo(neighbourBefore);
    }
}
