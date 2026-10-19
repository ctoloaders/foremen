package com.foremen.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.PaymentTransactionEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.service.payment.dto.mapper.PaymentDtoMapper;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import org.springframework.context.ApplicationEventPublisher;

/**
 * FOR-06-07 task 8.1 — Property 4 "AllocationIdempotency" for {@link ReconciliationService}
 * (design §ReconciliationService; Requirements 5.8, 8.3). Reconciling a confirmed transaction is
 * idempotent per transaction: the idempotency key is {@code (schedule, channel, externalRef)}, and
 * at the apply-layer a transaction that already carries allocations was reconciled before, so
 * re-processing it (a re-delivered webhook / re-polled bank line with the same key) is a <b>no-op</b>
 * — no second allocation row, no further {@code paidGross} bump (R5.8, R8.3).
 *
 * <p>The property drives the real pure collaborators ({@link ReconciliationEngine},
 * {@link TrancheStatusDeriver}) and the real cascade apply; only the persistence DAOs / event
 * publisher are mocked. For an arbitrary open-tranche schedule and an arbitrary confirmed amount, it
 * asserts the <b>second</b> {@code reconcile} of the same transaction leaves the schedule in exactly
 * the state the first left it: identical per-tranche {@code paidGross}, identical allocation count,
 * identical unallocated credit.
 *
 * <p><b>Validates: Requirements 5.8, 8.3</b>
 */
@Tag("Feature: FOR-06-07-client-payments, Property 4 AllocationIdempotency")
class AllocationIdempotencyPropertyTest {

    private static final int SCALE = 2;

    private ReconciliationService newService() {
        PaymentScheduleDao scheduleDao = mock(PaymentScheduleDao.class);
        when(scheduleDao.save(any(PaymentScheduleEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        PaymentTransactionDao txnDao = mock(PaymentTransactionDao.class);
        when(txnDao.save(any(PaymentTransactionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        UserDao userDao = mock(UserDao.class);
        PaymentDtoMapper mapper = mock(PaymentDtoMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        return new ReconciliationService(new ReconciliationEngine(), new TrancheStatusDeriver(),
                mapper, scheduleDao, txnDao, userDao, publisher);
    }

    /** A strictly-positive tranche amount in [0.01, 100000], scale 2. */
    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100000"))
                .ofScale(SCALE);
    }

    /** A 1..6-tranche fully-unpaid DRAFT→PUBLISHED schedule, seqNo 1..N by list order. */
    @Provide
    Arbitrary<List<BigDecimal>> trancheAmountSets() {
        return amounts().list().ofMinSize(1).ofMaxSize(6);
    }

    /** A non-negative amount to apply in [0, 300000], scale 2 (0, partial, exact, and overpay exercised). */
    @Provide
    Arbitrary<BigDecimal> payAmounts() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("300000"))
                .ofScale(SCALE);
    }

    private PaymentScheduleEntity schedule(List<BigDecimal> trancheAmounts) {
        PaymentScheduleEntity schedule = new PaymentScheduleEntity();
        schedule.setId(1L);
        ProjectEntity project = new ProjectEntity();
        project.setId(42L);
        schedule.setProject(project);
        schedule.setStatus(ScheduleStatus.PUBLISHED);
        schedule.setUnallocatedCredit(BigDecimal.ZERO);
        BigDecimal basis = BigDecimal.ZERO;
        int seq = 1;
        for (BigDecimal amount : trancheAmounts) {
            PaymentTrancheEntity tranche = new PaymentTrancheEntity();
            tranche.setId((long) seq);
            tranche.setSchedule(schedule);
            tranche.setSeqNo(seq);
            tranche.setDueDate(LocalDate.of(2099, 1, 1).plusMonths(seq));
            tranche.setAmountGross(amount.setScale(SCALE, RoundingMode.HALF_UP));
            tranche.setPaidGross(BigDecimal.ZERO);
            tranche.setStatus(TrancheStatus.PENDING);
            schedule.getTranches().add(tranche);
            basis = basis.add(tranche.getAmountGross());
            seq++;
        }
        schedule.setBasisGross(basis);
        return schedule;
    }

    private PaymentTransactionEntity confirmed(PaymentScheduleEntity schedule, BigDecimal amount) {
        PaymentTransactionEntity txn = new PaymentTransactionEntity();
        txn.setId(500L);
        txn.setSchedule(schedule);
        txn.setChannel(PaymentChannel.BANK_TRANSFER);
        txn.setExternalRef("ref-1");
        txn.setAmountGross(amount.setScale(SCALE, RoundingMode.HALF_UP));
        txn.setFeeGross(BigDecimal.ZERO);
        txn.setStatus(TransactionStatus.CONFIRMED);
        return txn;
    }

    // Feature: FOR-06-07-client-payments, Property 4 AllocationIdempotency
    // reconcile(txn) then reconcile(txn) again leaves identical per-tranche paidGross, identical
    // allocation count, identical unallocated credit — the second call allocates nothing (R5.8, R8.3).
    // Validates: Requirements 5.8, 8.3
    @Property(tries = 200)
    void reProcessingAConfirmedTransactionIsANoOp(
            @ForAll("trancheAmountSets") List<BigDecimal> trancheAmounts,
            @ForAll("payAmounts") BigDecimal payAmount) {
        ReconciliationService service = newService();
        PaymentScheduleEntity schedule = schedule(trancheAmounts);
        PaymentTransactionEntity txn = confirmed(schedule, payAmount);

        // First reconciliation applies the cascade.
        service.reconcile(txn);

        List<BigDecimal> paidAfterFirst = new ArrayList<>();
        for (PaymentTrancheEntity t : schedule.getTranches()) {
            paidAfterFirst.add(t.getPaidGross());
        }
        int allocationsAfterFirst = txn.getAllocations().size();
        BigDecimal creditAfterFirst = schedule.getUnallocatedCredit();

        // Second reconciliation of the SAME transaction must be a no-op (R5.8).
        service.reconcile(txn);

        assertThat(txn.getAllocations()).hasSize(allocationsAfterFirst);
        assertThat(schedule.getUnallocatedCredit()).isEqualByComparingTo(creditAfterFirst);
        List<PaymentTrancheEntity> tranches = schedule.getTranches();
        for (int i = 0; i < tranches.size(); i++) {
            assertThat(tranches.get(i).getPaidGross())
                    .isEqualByComparingTo(paidAfterFirst.get(i));
        }
    }
}
