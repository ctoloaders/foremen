package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import org.springframework.http.HttpStatus;

import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayoutAllocationDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerPayoutAllocationEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.WorkerPayoutServiceMapper;
import com.foremen.service.payroll.PayoutSuggestionService.PayableCandidate;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.payroll.dto.mapper.WorkerPayoutDtoMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-07-06 task 5.2 — <b>Properties 4–6</b> (design §Correctness properties) over the real
 * {@link WorkerPayoutService} + {@link AllocationWriter} + {@link PayoutDistributor} +
 * {@link AdvanceCreditCalculator}, backed by an in-memory store (the DAOs answer from maps; the
 * candidate selection mirrors {@code PayoutSuggestionService.payableLines}: lines in distribution
 * order whose {@code cost − paidAmount > 0}).
 *
 * <ul>
 *   <li><b>Property 4 — paid-amount consistency:</b> after any sequence of creates / mark-paids /
 *       deletes, every line's {@code paid_amount} equals Σ of its allocation amounts, is
 *       {@code ≥ 0}, and never exceeds the line cost (R5.9, R6.2).</li>
 *   <li><b>Property 5 — delete reversibility:</b> from any reachable state, creating and then
 *       deleting a {@code PLANNED} regular payout restores every {@code paid_amount}, the allocation
 *       set, and the worker's remaining credit (R6.2).</li>
 *   <li><b>Property 6 — worker-wide credit:</b> the remaining credit over all the worker's payouts is
 *       never negative, Σ {@code advanceApplied} of regulars never exceeds Σ advance amounts, and an
 *       advance delete is rejected ({@code error.workerPayout.advance.alreadyApplied}, state
 *       unchanged) exactly when removing it would break that bound (R6.3, R7.1, R7.2).</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 5.3, 5.6, 5.9, 6.2, 6.3, 7.1, 7.2</b>
 */
@Tag("Feature: FOR-07-06-company-payroll, Properties 4-6: allocation consistency, delete reversibility, worker-wide credit")
class AllocationReversiblePropertyTest {

    private static final Long WORKER_ID = 500L;
    private static final LocalDate FROM = LocalDate.now().minusMonths(1);
    private static final LocalDate REGULAR_TO = LocalDate.now().minusDays(1);
    private static final LocalDate ADVANCE_TO = LocalDate.now().plusMonths(1);

    // =============================================================================================
    // Property 4
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-06-company-payroll, Property 4: paid_amount = Σ allocations, 0 ≤ paid ≤ cost after any op sequence")
    void paidAmountAlwaysEqualsSumOfAllocations(@ForAll("costs") List<BigDecimal> costs,
                                                @ForAll("ops") List<Op> ops) {
        World w = new World(costs);
        for (Op op : ops) {
            w.apply(op);
            w.assertPaidAmountConsistent();
        }
    }

    // =============================================================================================
    // Property 5
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-06-company-payroll, Property 5: create then delete of a PLANNED regular payout is identity")
    void createThenDeleteRestoresState(@ForAll("costs") List<BigDecimal> costs,
                                       @ForAll("ops") List<Op> prefix,
                                       @ForAll("override") Optional<BigDecimal> override) {
        World w = new World(costs);
        prefix.forEach(w::apply);

        Map<Long, BigDecimal> paidBefore = w.paidSnapshot();
        int allocationsBefore = w.allocations.size();
        BigDecimal creditBefore = w.remainingCredit();

        Long id = w.service.create(new CreatePayoutRequest(WORKER_ID, null, FROM, REGULAR_TO,
                PaymentChannel.CASH, override.orElse(null), null)).id();
        w.assertPaidAmountConsistent();
        w.service.delete(id);

        assertThat(w.paidSnapshot())
                .as("every line's paid_amount is restored by the delete (R6.2)")
                .isEqualTo(paidBefore);
        assertThat(w.allocations).as("the payout's allocation rows are gone").hasSize(allocationsBefore);
        assertThat(w.remainingCredit())
                .as("the worker's remaining credit is restored (R6.2, R7.1)")
                .isEqualByComparingTo(creditBefore);
        assertThat(w.payouts).as("the payout is hard-deleted").doesNotContainKey(id);
    }

    // =============================================================================================
    // Property 6
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-06-company-payroll, Property 6: credit ≥ 0, Σ applied ≤ Σ advances; consumed-advance delete rejected")
    void workerWideCreditStaysConsistent(@ForAll("costs") List<BigDecimal> costs,
                                         @ForAll("ops") List<Op> ops) {
        World w = new World(costs);
        for (Op op : ops) {
            if (op.kind() == Kind.DELETE && w.target(op) != null && w.target(op).isAdvance()
                    && w.target(op).getStatus() == PayoutStatus.PLANNED) {
                WorkerPayoutEntity advance = w.target(op);
                boolean consumed = w.sumApplied().compareTo(w.sumAdvances().subtract(advance.getAmount())) > 0;
                Map<Long, BigDecimal> paidBefore = w.paidSnapshot();
                int payoutsBefore = w.payouts.size();
                boolean rejected = w.apply(op);
                assertThat(rejected)
                        .as("an advance delete is rejected exactly when its credit was already consumed (R6.3)")
                        .isEqualTo(consumed);
                if (rejected) {
                    assertThat(w.payouts).as("a rejected delete removes nothing").hasSize(payoutsBefore);
                    assertThat(w.paidSnapshot()).isEqualTo(paidBefore);
                }
            } else {
                w.apply(op);
            }
            assertThat(w.remainingCredit()).as("remaining credit is never negative (R7.2)")
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(w.sumApplied())
                    .as("Σ advanceApplied of regulars never exceeds Σ advances, worker-wide (R6.3, R7.1)")
                    .isLessThanOrEqualTo(w.sumAdvances());
        }
    }

    // =============================================================================================
    // In-memory world
    // =============================================================================================

    enum Kind { REGULAR, ADVANCE, PAY, DELETE }

    /** One generated operation; {@code amount} is the override (regular) / amount (advance). */
    record Op(Kind kind, BigDecimal amount, int index) {
    }

    private static final class World {
        final List<WorkReportRoomEntity> lines = new ArrayList<>();
        final Map<Long, BigDecimal> costs = new HashMap<>();
        final Map<Long, WorkerPayoutEntity> payouts = new LinkedHashMap<>();
        final List<WorkerPayoutAllocationEntity> allocations = new ArrayList<>();
        final AdvanceCreditCalculator calculator = new AdvanceCreditCalculator();
        final WorkerPayoutService service;
        long payoutSeq = 0;
        long allocationSeq = 0;

        World(List<BigDecimal> lineCosts) {
            for (int i = 0; i < lineCosts.size(); i++) {
                WorkReportRoomEntity line = new WorkReportRoomEntity();
                line.setId(100L + i);
                line.setPaidAmount(BigDecimal.ZERO.setScale(2));
                lines.add(line);
                costs.put(line.getId(), lineCosts.get(i));
            }

            WorkerPayoutDao payoutDao = mock(WorkerPayoutDao.class);
            lenient().when(payoutDao.save(any(WorkerPayoutEntity.class))).thenAnswer(inv -> {
                WorkerPayoutEntity p = inv.getArgument(0);
                if (p.getId() == null) {
                    p.setId(++payoutSeq);
                }
                payouts.put(p.getId(), p);
                return p;
            });
            lenient().when(payoutDao.findById(anyLong()))
                    .thenAnswer(inv -> Optional.ofNullable(payouts.get((Long) inv.getArgument(0))));
            lenient().when(payoutDao.findByWorker(eq(WORKER_ID)))
                    .thenAnswer(inv -> new ArrayList<>(payouts.values()));
            doAnswer(inv -> payouts.remove((Long) inv.getArgument(0))).when(payoutDao).deleteById(anyLong());

            WorkerPayoutAllocationDao allocationDao = mock(WorkerPayoutAllocationDao.class);
            lenient().when(allocationDao.save(any(WorkerPayoutAllocationEntity.class))).thenAnswer(inv -> {
                WorkerPayoutAllocationEntity a = inv.getArgument(0);
                a.setId(++allocationSeq);
                allocations.add(a);
                return a;
            });
            lenient().when(allocationDao.findByPayoutId(anyLong())).thenAnswer(inv -> {
                Long payoutId = inv.getArgument(0);
                return allocations.stream().filter(a -> payoutId.equals(a.getPayout().getId())).toList();
            });
            doAnswer(inv -> {
                Iterable<?> rows = inv.getArgument(0);
                List<Object> toRemove = new ArrayList<>();
                rows.forEach(toRemove::add);
                allocations.removeAll(toRemove);
                return null;
            }).when(allocationDao).deleteAll(any(Iterable.class));

            UserDao userDao = mock(UserDao.class);
            UserEntity worker = new UserEntity();
            worker.setId(WORKER_ID);
            RoleEntity role = new RoleEntity();
            role.setCode("WORKER");
            worker.setRole(role);
            lenient().when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(worker));

            // Mirrors PayoutSuggestionService.payableLines: distribution order, remainder > 0 only.
            PayoutSuggestionService suggestionService = mock(PayoutSuggestionService.class);
            lenient().when(suggestionService.payableLines(eq(WORKER_ID), any())).thenAnswer(inv -> {
                List<PayableCandidate> out = new ArrayList<>();
                for (WorkReportRoomEntity line : lines) {
                    BigDecimal remainder = costs.get(line.getId()).subtract(line.getPaidAmount())
                            .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                    if (remainder.signum() > 0) {
                        out.add(new PayableCandidate(line, remainder));
                    }
                }
                return out;
            });
            lenient().when(suggestionService.suggest(eq(WORKER_ID), any(), any(), eq(true)))
                    .thenReturn(new PayoutSuggestionDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO.setScale(2), true, List.of()));

            // The DTO mapper is a mock; create() must still return the id for Property 5.
            WorkerPayoutDtoMapper dtoMapper = mock(WorkerPayoutDtoMapper.class);
            lenient().when(dtoMapper.toDto(any(WorkerPayoutEntity.class))).thenAnswer(inv -> {
                WorkerPayoutEntity p = inv.getArgument(0);
                return new com.foremen.service.payroll.dto.WorkerPayoutDto(p.getId(), WORKER_ID, null,
                        p.getPayoutDate(), p.getPeriodFrom(), p.getPeriodTo(), p.getAmount(),
                        p.getSuggestedAmount(), p.getAdvanceApplied(), p.getPaymentChannel(), p.getStatus(),
                        p.isAdvance(), p.getPaidAt(), p.getNote(), List.of());
            });

            EntityManager em = mock(EntityManager.class);
            service = new WorkerPayoutService(payoutDao, allocationDao, userDao, suggestionService,
                    new AllocationWriter(allocationDao, em), calculator, new AdvanceDeriver(),
                    mock(WorkerPayoutServiceMapper.class), dtoMapper, mock(AuditLogDao.class), em,
                    mock(org.springframework.context.ApplicationEventPublisher.class));
        }

        /** Applies the op; returns {@code true} when the service rejected it with a 409. */
        boolean apply(Op op) {
            try {
                switch (op.kind()) {
                    case REGULAR -> service.create(new CreatePayoutRequest(WORKER_ID, null, FROM, REGULAR_TO,
                            PaymentChannel.BANK_TRANSFER, op.amount(), null));
                    case ADVANCE -> service.create(new CreatePayoutRequest(WORKER_ID, null, FROM, ADVANCE_TO,
                            PaymentChannel.CASH, op.amount() == null ? BigDecimal.TEN : op.amount(), null));
                    case PAY -> {
                        WorkerPayoutEntity p = target(op);
                        if (p != null) {
                            service.markPaid(p.getId());
                        }
                    }
                    case DELETE -> {
                        WorkerPayoutEntity p = target(op);
                        if (p != null) {
                            service.delete(p.getId());
                        }
                    }
                }
                return false;
            } catch (ForemenApiException e) {
                assertThat(e.getStatus().value()).as("only lifecycle conflicts are expected")
                        .isEqualTo(HttpStatus.CONFLICT.value());
                return true;
            }
        }

        WorkerPayoutEntity target(Op op) {
            if (payouts.isEmpty()) {
                return null;
            }
            List<WorkerPayoutEntity> all = new ArrayList<>(payouts.values());
            return all.get(Math.floorMod(op.index(), all.size()));
        }

        void assertPaidAmountConsistent() {
            for (WorkReportRoomEntity line : lines) {
                BigDecimal sum = allocations.stream()
                        .filter(a -> a.getWorkReportRoom().getId().equals(line.getId()))
                        .map(WorkerPayoutAllocationEntity::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(line.getPaidAmount())
                        .as("paid_amount = Σ allocations for line %s (R5.9)", line.getId())
                        .isEqualByComparingTo(sum);
                assertThat(line.getPaidAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                assertThat(line.getPaidAmount())
                        .as("paid_amount never exceeds the line cost (R5.9)")
                        .isLessThanOrEqualTo(costs.get(line.getId()));
            }
            for (WorkerPayoutAllocationEntity a : allocations) {
                assertThat(a.getAmount().signum()).as("allocation amount > 0").isPositive();
                assertThat(a.getPayout().isAdvance()).as("an advance has no allocations (R5.6)").isFalse();
            }
        }

        Map<Long, BigDecimal> paidSnapshot() {
            Map<Long, BigDecimal> out = new LinkedHashMap<>();
            for (WorkReportRoomEntity line : lines) {
                out.put(line.getId(), line.getPaidAmount().setScale(2, RoundingMode.HALF_UP));
            }
            return out;
        }

        BigDecimal remainingCredit() {
            return calculator.remaining(new ArrayList<>(payouts.values()));
        }

        BigDecimal sumApplied() {
            return payouts.values().stream().filter(p -> !p.isAdvance())
                    .map(WorkerPayoutEntity::getAdvanceApplied).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal sumAdvances() {
            return payouts.values().stream().filter(WorkerPayoutEntity::isAdvance)
                    .map(WorkerPayoutEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    // =============================================================================================
    // generators
    // =============================================================================================

    private static Arbitrary<BigDecimal> cents(long min, long max) {
        return Arbitraries.longs().between(min, max).map(c -> BigDecimal.valueOf(c, 2));
    }

    @Provide
    Arbitrary<List<BigDecimal>> costs() {
        return cents(1L, 50_000L).list().ofMinSize(1).ofMaxSize(6);
    }

    @Provide
    Arbitrary<Optional<BigDecimal>> override() {
        return cents(0L, 200_000L).optional(0.5);
    }

    @Provide
    Arbitrary<List<Op>> ops() {
        Arbitrary<Kind> kinds = Arbitraries.of(Kind.class);
        Arbitrary<BigDecimal> amounts = Arbitraries.oneOf(
                Arbitraries.just((BigDecimal) null),
                cents(0L, 100_000L));
        Arbitrary<Integer> indices = Arbitraries.integers().between(0, 50);
        return Combinators.combine(kinds, amounts, indices).as(Op::new).list().ofMinSize(0).ofMaxSize(12);
    }
}
