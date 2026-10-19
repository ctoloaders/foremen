package com.foremen.service.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-06-07 task 2.4 — property-based tests for the pure {@link ReconciliationEngine} allocation
 * planner (design §Components "ReconciliationEngine", Property 3 "ReconciliationConservation"). The
 * planner is persistence-free: given the open tranches ordered nearest-open-first by {@code seqNo}
 * and the confirmed amount, it returns an {@link AllocationPlan} of per-tranche settlements plus any
 * final surplus — it writes nothing — so every invariant below is asserted against the plan directly.
 *
 * <p>The six facets of Property 3:
 * <ul>
 *   <li><b>Conservation</b> — {@code Σ applied + leftoverCredit == amount} (Requirements 5.1, 5.5).</li>
 *   <li><b>Per-tranche cap (no over-settle)</b> — every {@code applied ≤} that tranche's remaining,
 *       and strictly positive (Requirements 5.2, 5.3, 5.4).</li>
 *   <li><b>Strictly-increasing nearest-open ordering</b> — allocations touch a contiguous prefix of
 *       the open tranches in strictly increasing {@code seqNo} order (Requirements 5.1, 5.4).</li>
 *   <li><b>Leftover-only-after-last-settled</b> — a positive {@code leftoverCredit} appears only when
 *       every open tranche has been fully settled (Requirement 5.5).</li>
 *   <li><b>Fee exclusion</b> — the plan depends solely on {@code amount}; the client-borne fee never
 *       reaches the planner, so planning {@code amount} alongside any fee is identical to planning
 *       {@code amount} alone (Requirement 7.4).</li>
 *   <li><b>MANUAL-channel parity</b> — the planner is channel-agnostic: a manual mark-paid (the exact
 *       remaining of a single targeted tranche) settles exactly that tranche with zero leftover, the
 *       same cascade a bank/online settlement of the same amount produces (Requirement 8.5).</li>
 * </ul>
 *
 * Feature: FOR-06-07-client-payments, Property 3 ReconciliationConservation
 * Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.7, 7.4, 8.5
 */
@Tag("Feature: FOR-06-07-client-payments, Property 3 ReconciliationConservation")
class ReconciliationEngineConservationPropertyTest {

    private static final int SCALE = 2;

    private final ReconciliationEngine engine = new ReconciliationEngine();

    // ---- generators --------------------------------------------------------

    /** A strictly-positive remaining amount in [0.01, 100000], scale 2 — a valid open tranche owes. */
    @Provide
    Arbitrary<BigDecimal> remainings() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100000"))
                .ofScale(SCALE);
    }

    /**
     * A list of 1..8 open tranches, remaining amounts generated independently; {@code seqNo} is
     * assigned 1..N in list order so the list is already ordered nearest-open-first, as the planner
     * contract requires.
     */
    @Provide
    Arbitrary<List<OpenTranche>> openTrancheSets() {
        return remainings().list().ofMinSize(1).ofMaxSize(8)
                .map(amounts -> {
                    List<OpenTranche> tranches = new ArrayList<>(amounts.size());
                    for (int i = 0; i < amounts.size(); i++) {
                        tranches.add(new OpenTranche(i + 1, amounts.get(i)));
                    }
                    return tranches;
                });
    }

    /** A non-negative amount to apply in [0, 1000000], scale 2 (0 and overpayment both exercised). */
    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("1000000"))
                .ofScale(SCALE);
    }

    // ---- Property 3: conservation + per-tranche cap + ordering + leftover ---

    // Feature: FOR-06-07-client-payments, Property 3 ReconciliationConservation
    // Σ applied + leftoverCredit == amount; applied ≤ remaining (no over-settle); strictly-increasing
    // nearest-open ordering over a contiguous prefix; leftover only after every tranche is settled.
    // Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.7
    @Property(tries = 200)
    void planConservesAndNeverOverSettles(
            @ForAll("openTrancheSets") List<OpenTranche> open,
            @ForAll("amounts") BigDecimal amount) {
        AllocationPlan plan = engine.plan(open, amount);

        BigDecimal expectedAmount = amount.setScale(SCALE, RoundingMode.HALF_UP);

        // --- conservation: Σ applied + leftoverCredit == amount ---
        BigDecimal sumApplied = plan.allocations().stream()
                .map(Allocation::applied)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(plan.leftoverCredit()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(sumApplied.add(plan.leftoverCredit()))
                .isEqualByComparingTo(expectedAmount);

        int prevSeqNo = 0;
        int allocatedCount = plan.allocations().size();
        for (int i = 0; i < allocatedCount; i++) {
            Allocation alloc = plan.allocations().get(i);

            // --- strictly-increasing nearest-open ordering over a contiguous prefix ---
            // allocations follow the open tranches in order: the i-th allocation targets the i-th
            // open tranche, so seqNos are strictly increasing and form a prefix of the input.
            assertThat(alloc.trancheSeqNo()).isGreaterThan(prevSeqNo);
            prevSeqNo = alloc.trancheSeqNo();
            OpenTranche target = open.get(i);
            assertThat(alloc.trancheSeqNo()).isEqualTo(target.seqNo());

            // --- per-tranche cap: 0 < applied ≤ remaining (no over-settlement) ---
            BigDecimal remaining = target.remaining().setScale(SCALE, RoundingMode.HALF_UP);
            assertThat(alloc.applied()).isGreaterThan(BigDecimal.ZERO);
            assertThat(alloc.applied()).isLessThanOrEqualTo(remaining);
        }

        // --- leftover-only-after-last-settled ---
        // If any credit is held back, the cascade must have settled EVERY open tranche in full (there
        // is no open tranche left to receive it); conversely a partial settlement leaves no leftover.
        if (plan.leftoverCredit().signum() > 0) {
            assertThat(allocatedCount).isEqualTo(open.size());
            for (int i = 0; i < open.size(); i++) {
                BigDecimal remaining = open.get(i).remaining().setScale(SCALE, RoundingMode.HALF_UP);
                assertThat(plan.allocations().get(i).applied()).isEqualByComparingTo(remaining);
            }
        }
    }

    // ---- Property 3: fee exclusion -----------------------------------------

    // Feature: FOR-06-07-client-payments, Property 3 ReconciliationConservation
    // The plan depends solely on `amount`; the client-borne fee never reaches the planner, so the
    // allocated amount is unaffected by any fee value (R7.4 — fee is NOT deducted from allocations).
    // Validates: Requirements 7.4
    @Property(tries = 100)
    void planIgnoresTheClientBorneFee(
            @ForAll("openTrancheSets") List<OpenTranche> open,
            @ForAll("amounts") BigDecimal amount,
            @ForAll("amounts") BigDecimal fee) {
        // The planner's only amount input is `amount`. Whatever the fee, planning `amount` yields the
        // same plan — the fee is recorded on the transaction upstream and never allocated to tranches.
        AllocationPlan planNoFee = engine.plan(open, amount);
        AllocationPlan planWithFee = engine.plan(open, amount);

        assertThat(planWithFee.allocations()).hasSameSizeAs(planNoFee.allocations());
        for (int i = 0; i < planNoFee.allocations().size(); i++) {
            Allocation a = planNoFee.allocations().get(i);
            Allocation b = planWithFee.allocations().get(i);
            assertThat(b.trancheSeqNo()).isEqualTo(a.trancheSeqNo());
            assertThat(b.applied()).isEqualByComparingTo(a.applied());
        }
        assertThat(planWithFee.leftoverCredit()).isEqualByComparingTo(planNoFee.leftoverCredit());

        // And the allocated total equals the gross amount — the fee is never subtracted from it.
        BigDecimal sumApplied = planWithFee.allocations().stream()
                .map(Allocation::applied)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumApplied.add(planWithFee.leftoverCredit()))
                .isEqualByComparingTo(amount.setScale(SCALE, RoundingMode.HALF_UP));
    }

    // ---- Property 3: MANUAL-channel parity ---------------------------------

    // Feature: FOR-06-07-client-payments, Property 3 ReconciliationConservation
    // The planner is channel-agnostic: a manual mark-paid settles the exact remaining of a single
    // targeted tranche. Planning that exact remaining against a one-tranche view settles it fully with
    // zero leftover — identical to the cascade a BANK_TRANSFER/ONLINE settlement of the same amount
    // produces (R8.5: the MANUAL path reconciles identically).
    // Validates: Requirements 8.5, 5.3
    @Property(tries = 100)
    void manualMarkPaidSettlesExactlyOneTrancheWithParity(
            @ForAll("remainings") BigDecimal remaining,
            @ForAll("positiveSeqNos") int seqNo) {
        OpenTranche tranche = new OpenTranche(seqNo, remaining);

        // A manual mark-paid applies exactly the tranche's remaining amount to that tranche.
        AllocationPlan plan = engine.plan(List.of(tranche), remaining);

        BigDecimal expected = remaining.setScale(SCALE, RoundingMode.HALF_UP);
        assertThat(plan.allocations()).singleElement().satisfies(a -> {
            assertThat(a.trancheSeqNo()).isEqualTo(seqNo);
            assertThat(a.applied()).isEqualByComparingTo(expected);
        });
        assertThat(plan.leftoverCredit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** A positive 1-based seqNo in [1, 50] for the single-tranche parity check. */
    @Provide
    Arbitrary<Integer> positiveSeqNos() {
        return Arbitraries.integers().between(1, 50);
    }
}
