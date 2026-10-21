package com.foremen.service.workercost;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.estimate.margins.WorkerTypeTier;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-06-10 (design §Correctness Properties, Property 5; task 5.1) — property-based tests for the
 * worker-cost <strong>aggregation fold</strong>: that the per-worker and per-work-item aggregates equal
 * the sum of their per-report {@code Cost_Value} parts over the <em>available-cost</em> reports, that
 * an unavailable-cost (null/zero-rate) report contributes to no aggregate, and that re-assigning one
 * report to a different worker type changes only that report's contribution.
 *
 * <p>The {@code WorkerCostService} folds its {@code byWorker} / {@code byWorkItem} aggregates over the
 * project's APPROVED reports, skipping any line whose {@code Cost_Value} is {@code null} (design §B1,
 * R3.2, R3.4). The aggregation arithmetic is a pure fold over the per-report {@link WorkerCostLineDto}
 * costs, so these properties exercise it directly in memory — mirroring the service's exact skip rule
 * ({@code if (costValue != null)}), composed from the single cost formula
 * {@link WorkerCostDerivation#costValue} (the cost-math / resolution properties themselves live in
 * tasks 1.1a / 2.1a). The APPROVED-only filter (a non-APPROVED report never produces a line) is a read
 * concern asserted by the integration test; here we assert the fold identity the service relies on.
 *
 * <p>Minimum 100 iterations each, per the design test strategy.
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4</b>
 */
@Tag("Feature: FOR-06-10-worker-cost-rates, Property 5: Aggregates equal the sum of their per-report parts (approved only)")
class WorkerCostAggregationPropertyTest {

    private static final BigDecimal BASE_SHARE = new BigDecimal("0.40");
    private static final WorkerTypeTier BASE_TIER = WorkerTypeTier.base(BASE_SHARE);

    // =============================================================================================
    // Property 5 — Per-worker and per-work-item aggregates equal the sum of the per-report parts.
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-10-worker-cost-rates, Property 5: Aggregates equal the sum of their per-report parts (approved only)")
    void perWorkerAndPerWorkItemAggregatesEqualTheSumOfAvailableCostLines(
            @ForAll("reports") List<FakeReport> reports) {

        List<WorkerCostLineDto> lines = deriveLines(reports);
        Map<Long, BigDecimal> byWorker = foldByWorker(lines);
        Map<Long, BigDecimal> byWorkItem = foldByWorkItem(lines);

        // Reference: independently sum the available-cost lines grouped by worker / work item.
        Map<Long, BigDecimal> expectedByWorker = new LinkedHashMap<>();
        Map<Long, BigDecimal> expectedByWorkItem = new LinkedHashMap<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (WorkerCostLineDto line : lines) {
            if (line.costValue() == null) {
                continue; // an unavailable-cost report contributes to no aggregate (R3.4)
            }
            expectedByWorker.merge(line.workerId(), line.costValue(), BigDecimal::add);
            expectedByWorkItem.merge(line.workItemId(), line.costValue(), BigDecimal::add);
            grandTotal = grandTotal.add(line.costValue());
        }

        assertThat(byWorker.keySet())
                .as("the per-worker aggregate must cover exactly the workers with an available cost")
                .isEqualTo(expectedByWorker.keySet());
        expectedByWorker.forEach((workerId, expected) ->
                assertThat(byWorker.get(workerId))
                        .as("per-worker(%s) == Σ of that worker's available Cost_Value parts (R3.2)", workerId)
                        .isEqualByComparingTo(expected));

        assertThat(byWorkItem.keySet())
                .as("the per-work-item aggregate must cover exactly the work items with an available cost")
                .isEqualTo(expectedByWorkItem.keySet());
        expectedByWorkItem.forEach((workItemId, expected) ->
                assertThat(byWorkItem.get(workItemId))
                        .as("per-workItem(%s) == Σ of that item's available Cost_Value parts (R3.2)", workItemId)
                        .isEqualByComparingTo(expected));

        // Both decompositions of the same multiset sum to the same grand total (R3.1, R3.2).
        assertThat(byWorker.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("Σ per-worker == Σ per-workItem == Σ of all available per-report Cost_Value (R3.1)")
                .isEqualByComparingTo(grandTotal);
        assertThat(byWorkItem.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(grandTotal);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-10-worker-cost-rates, Property 5: Aggregates equal the sum of their per-report parts (approved only)")
    void addingAnUnavailableRateReportLeavesEveryAggregateUnchanged(
            @ForAll("reports") List<FakeReport> reports,
            @ForAll("zeroOrNullRate") BigDecimal unavailableRate,
            @ForAll("quantity") BigDecimal quantity) {

        Map<Long, BigDecimal> baselineByWorker = foldByWorker(deriveLines(reports));
        Map<Long, BigDecimal> baselineByWorkItem = foldByWorkItem(deriveLines(reports));

        // Add one report with a null/zero rate (an unavailable cost, R1.5) for a brand-new worker/item.
        List<FakeReport> withUnavailable = new ArrayList<>(reports);
        withUnavailable.add(new FakeReport(9_000L, 9_000L, false, unavailableRate, quantity));

        assertThat(foldByWorker(deriveLines(withUnavailable)))
                .as("an unavailable-rate report must be excluded from the per-worker sums (R3.4)")
                .isEqualTo(baselineByWorker);
        assertThat(foldByWorkItem(deriveLines(withUnavailable)))
                .as("an unavailable-rate report must be excluded from the per-work-item sums (R3.4)")
                .isEqualTo(baselineByWorkItem);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-10-worker-cost-rates, Property 5: Aggregates equal the sum of their per-report parts (approved only)")
    void reassigningOneReportsTierChangesOnlyThatReportsContribution(
            @ForAll("reports") List<FakeReport> reports,
            @ForAll("index") int rawIndex,
            @ForAll("uplift") BigDecimal newUplift) {

        if (reports.isEmpty()) {
            return;
        }
        int idx = Math.floorMod(rawIndex, reports.size());
        FakeReport original = reports.get(idx);

        // Re-assign report `idx` to a non-base tier (newUplift) but keep its worker/item/rate/quantity.
        List<FakeReport> mutated = new ArrayList<>(reports);
        mutated.set(idx, new FakeReport(
                original.workerId(), original.workItemId(), false, original.rate(), original.quantity(),
                newUplift));

        Map<Long, BigDecimal> beforeByWorker = foldByWorker(deriveLines(reports));
        Map<Long, BigDecimal> afterByWorker = foldByWorker(deriveLines(mutated));

        BigDecimal originalCost = costOf(original);
        BigDecimal mutatedCost = costOf(mutated.get(idx));
        BigDecimal delta = nz(mutatedCost).subtract(nz(originalCost));

        // Only the re-assigned report's worker bucket changes, and only by exactly its cost delta.
        for (Long workerId : union(beforeByWorker.keySet(), afterByWorker.keySet())) {
            BigDecimal before = beforeByWorker.getOrDefault(workerId, null);
            BigDecimal after = afterByWorker.getOrDefault(workerId, null);
            if (workerId.equals(original.workerId())) {
                // The affected bucket moves by exactly the delta (handling the appear/disappear edges
                // when the cost toggles availability — not possible here since rate/qty are unchanged).
                assertThat(nz(after).subtract(nz(before)))
                        .as("the re-assigned report's worker bucket moves by exactly its cost delta (R3.2)")
                        .isEqualByComparingTo(delta);
            } else {
                assertThat(after)
                        .as("an unrelated worker bucket (%s) is unchanged by the re-assignment", workerId)
                        .isEqualTo(before);
            }
        }
    }

    // =============================================================================================
    // Pure fold + derivation mirroring the service (the single cost formula via WorkerCostDerivation)
    // =============================================================================================

    /** Derive the per-report lines exactly as {@code WorkerCostService.costForProject} does. */
    private static List<WorkerCostLineDto> deriveLines(List<FakeReport> reports) {
        List<WorkerCostLineDto> lines = new ArrayList<>(reports.size());
        for (FakeReport r : reports) {
            WorkerTypeTier tier = r.base() ? BASE_TIER : WorkerTypeTier.uplift(r.uplift());
            BigDecimal unit = WorkerCostDerivation.unitServiceCost(r.rate(), tier, BASE_TIER);
            BigDecimal cost = WorkerCostDerivation.costValue(r.rate(), tier, BASE_TIER, r.quantity());
            lines.add(new WorkerCostLineDto(
                    null, r.workerId(), "w" + r.workerId(), r.workItemId(), "i" + r.workItemId(),
                    null, "t", r.base(), r.rate(), r.quantity(), unit, cost));
        }
        return lines;
    }

    private static BigDecimal costOf(FakeReport r) {
        WorkerTypeTier tier = r.base() ? BASE_TIER : WorkerTypeTier.uplift(r.uplift());
        return WorkerCostDerivation.costValue(r.rate(), tier, BASE_TIER, r.quantity());
    }

    /** Mirror the service's skip rule: fold only lines whose {@code costValue} is available (R3.4). */
    private static Map<Long, BigDecimal> foldByWorker(List<WorkerCostLineDto> lines) {
        Map<Long, BigDecimal> byWorker = new LinkedHashMap<>();
        for (WorkerCostLineDto line : lines) {
            if (line.costValue() != null) {
                byWorker.merge(line.workerId(), line.costValue(), BigDecimal::add);
            }
        }
        return byWorker;
    }

    private static Map<Long, BigDecimal> foldByWorkItem(List<WorkerCostLineDto> lines) {
        Map<Long, BigDecimal> byWorkItem = new LinkedHashMap<>();
        for (WorkerCostLineDto line : lines) {
            if (line.costValue() != null) {
                byWorkItem.merge(line.workItemId(), line.costValue(), BigDecimal::add);
            }
        }
        return byWorkItem;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static <T> List<T> union(java.util.Set<T> a, java.util.Set<T> b) {
        java.util.LinkedHashSet<T> out = new java.util.LinkedHashSet<>(a);
        out.addAll(b);
        return new ArrayList<>(out);
    }

    // =============================================================================================
    // A minimal in-memory report: the few fields the cost fold reads.
    // =============================================================================================

    record FakeReport(Long workerId, Long workItemId, boolean base, BigDecimal rate,
                      BigDecimal quantity, BigDecimal uplift) {
        FakeReport(Long workerId, Long workItemId, boolean base, BigDecimal rate, BigDecimal quantity) {
            this(workerId, workItemId, base, rate, quantity, BigDecimal.ZERO);
        }
    }

    // =============================================================================================
    // Generators
    // =============================================================================================

    @Provide
    Arbitrary<List<FakeReport>> reports() {
        return report().list().ofMinSize(0).ofMaxSize(8);
    }

    private Arbitrary<FakeReport> report() {
        Arbitrary<Long> workerId = Arbitraries.longs().between(1L, 4L);
        Arbitrary<Long> workItemId = Arbitraries.longs().between(1L, 3L);
        Arbitrary<Boolean> base = Arbitraries.of(true, false);
        // Mix available (positive) and unavailable (zero) rates so the fold's skip rule is exercised.
        Arbitrary<BigDecimal> rate = Arbitraries.oneOf(
                positiveRate(),
                Arbitraries.just(BigDecimal.ZERO));
        Arbitrary<BigDecimal> quantity = quantity();
        Arbitrary<BigDecimal> uplift = uplift();
        return Combinators.combine(workerId, workItemId, base, rate, quantity, uplift)
                .as(FakeReport::new);
    }

    @Provide
    Arbitrary<BigDecimal> zeroOrNullRate() {
        return Arbitraries.oneOf(Arbitraries.just(BigDecimal.ZERO), Arbitraries.just((BigDecimal) null));
    }

    @Provide
    Arbitrary<BigDecimal> quantity() {
        return Arbitraries.integers().between(0, 500)
                .map(i -> new BigDecimal(i).movePointLeft(1)); // 0.0 .. 50.0
    }

    @Provide
    Arbitrary<Integer> index() {
        return Arbitraries.integers().between(0, 1000);
    }

    @Provide
    Arbitrary<BigDecimal> uplift() {
        return Arbitraries.integers().between(0, 200).map(i -> new BigDecimal(i).movePointLeft(2)); // 0.00..2.00
    }

    private Arbitrary<BigDecimal> positiveRate() {
        return Arbitraries.integers().between(1, 100_000)
                .map(i -> new BigDecimal(i).movePointLeft(2)); // 0.01 .. 1000.00
    }
}
