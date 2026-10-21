package com.foremen.service.workercost;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.estimate.margins.WorkerTypeTier;
import com.foremen.service.execution.marginmatrix.MarginCategoryGroupDto;
import com.foremen.service.execution.marginmatrix.MarginMatrixAssembler;
import com.foremen.service.execution.marginmatrix.MarginMatrixDto;
import com.foremen.service.execution.marginmatrix.MarginMatrixInputs.ApprovedReport;
import com.foremen.service.execution.marginmatrix.MarginMatrixInputs.Bundle;
import com.foremen.service.execution.marginmatrix.MarginMatrixInputs.PlanLine;
import com.foremen.service.execution.marginmatrix.MarginReportDetailDto;
import com.foremen.service.execution.marginmatrix.MarginRowDto;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-06-10 (design §Correctness Properties, Property 6; task 5.1) — property-based verification of the
 * <strong>FOR-06-06 parity (single formula)</strong>: for any approved report, the fact cost the
 * FOR-06-06 margin matrix produces after the refactor equals {@code WorkerCostService}'s
 * {@code Cost_Value(report)} penny-for-penny, with the identical base-tier fallback — so the refactor
 * (task 4.1) changes no observable number (R4.1, R4.2, R4.3).
 *
 * <p>The refactored {@link MarginMatrixAssembler} delegates its fact-side labour cost to the shared
 * {@link WorkerCostDerivation#costValue} seam (the one {@code MarginCostService} core). This property
 * drives the <b>real</b> assembler over a generated {@link Bundle} and asserts, for every per-report
 * detail row, that the matrix's {@code derivedTierCost} equals the worker-cost service's per-report
 * {@code Cost_Value} computed through the shared derivation — and that the row's {@code factCost}
 * equals the sum of those per-report costs (R4.4). This is the exact equality the FOR-06-11 payroll
 * also relies on, pinning the single-formula guarantee.
 *
 * <p>Minimum 100 iterations each, per the design test strategy.
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.3</b>
 */
@Tag("Feature: FOR-06-10-worker-cost-rates, Property 6: FOR-06-06 parity (single formula)")
class WorkerCostFor0606ParityPropertyTest {

    private static final BigDecimal BASE_SHARE = new BigDecimal("0.40");
    private static final WorkerTypeTier BASE_TIER = WorkerTypeTier.base(BASE_SHARE);
    private static final Long WORK_ITEM_ID = 1L;

    private final MarginMatrixAssembler assembler = new MarginMatrixAssembler();

    // =============================================================================================
    // Property 6 — the matrix's per-report fact cost == the shared derivation's Cost_Value.
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-10-worker-cost-rates, Property 6: FOR-06-06 parity (single formula)")
    void matrixDetailCostEqualsWorkerCostDerivationPennyForPenny(
            @ForAll("reports") List<FakeReport> reports) {

        Bundle bundle = bundleFor(reports);
        MarginMatrixDto matrix = assembler.assemble(bundle);

        MarginRowDto row = singleRow(matrix);
        Map<Long, MarginReportDetailDto> detailByReportId = new LinkedHashMap<>();
        if (row != null) {
            for (MarginReportDetailDto d : row.reports()) {
                detailByReportId.put(d.reportId(), d);
            }
        }

        BigDecimal expectedFactCost = BigDecimal.ZERO;
        for (FakeReport r : reports) {
            // The shared worker-cost seam (what WorkerCostService.costFor delegates to), with the
            // assembler's null-safe quantity (nz) so the two agree for a null quantity too.
            WorkerTypeTier tier = tierOf(r);
            BigDecimal serviceCost =
                    WorkerCostDerivation.costValue(r.rate(), tier, BASE_TIER, nz(r.quantity()));

            MarginReportDetailDto detail = detailByReportId.get(r.reportId());
            assertThat(detail)
                    .as("every approved report must appear as a matrix detail row (report %s)", r.reportId())
                    .isNotNull();

            if (serviceCost == null) {
                assertThat(detail.derivedTierCost())
                        .as("an unavailable-rate report is unavailable on both sides (report %s)", r.reportId())
                        .isNull();
            } else {
                assertThat(detail.derivedTierCost())
                        .as("matrix derivedTierCost == WorkerCostDerivation.costValue penny-for-penny "
                                + "(report %s, R4.1/R4.2)", r.reportId())
                        .isEqualByComparingTo(serviceCost);
                expectedFactCost = expectedFactCost.add(serviceCost);
            }
        }

        if (row != null) {
            assertThat(row.factCost())
                    .as("the row fact cost equals the Σ of the shared-derivation per-report costs (R4.4)")
                    .isEqualByComparingTo(expectedFactCost);
        }
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-10-worker-cost-rates, Property 6: FOR-06-06 parity (single formula)")
    void baseTierFallbackIsIdenticalOnBothSides(@ForAll("reports") List<FakeReport> reports) {
        Bundle bundle = bundleFor(reports);
        MarginRowDto row = singleRow(assembler.assemble(bundle));
        if (row == null) {
            return;
        }
        Map<Long, FakeReport> byId = new LinkedHashMap<>();
        for (FakeReport r : reports) {
            byId.put(r.reportId(), r);
        }
        for (MarginReportDetailDto detail : row.reports()) {
            FakeReport r = byId.get(detail.reportId());
            // A base-tier (Uncategorized/fallback) report costs at the base tier on both sides, so its
            // detail cost equals the base-tier derivation — the identical fallback the service uses.
            if (r.base()) {
                assertThat(detail.baseTierFallback())
                        .as("a base-tier report is flagged baseTierFallback on the matrix side (report %s)",
                                r.reportId())
                        .isTrue();
                BigDecimal baseCost =
                        WorkerCostDerivation.costValue(r.rate(), BASE_TIER, BASE_TIER, nz(r.quantity()));
                if (baseCost == null) {
                    assertThat(detail.derivedTierCost()).isNull();
                } else {
                    assertThat(detail.derivedTierCost())
                            .as("the fallback report costs at the base tier identically on both sides (R4.2)")
                            .isEqualByComparingTo(baseCost);
                }
            }
        }
    }

    // =============================================================================================
    // Bundle building — one priced plan line + the generated reports, all on a single work item.
    // =============================================================================================

    private Bundle bundleFor(List<FakeReport> reports) {
        PlanLine plan = new PlanLine(
                WORK_ITEM_ID, "Praca", 10L, "Kategoria", 1, "m2",
                new BigDecimal("100.00"), new BigDecimal("1"), new BigDecimal("100.00"));

        List<ApprovedReport> approved = new ArrayList<>(reports.size());
        for (FakeReport r : reports) {
            WorkerTypeTier tier = tierOf(r);
            approved.add(new ApprovedReport(
                    r.reportId(),
                    WORK_ITEM_ID,
                    r.workerId(),
                    "w" + r.workerId(),
                    r.base() ? null : 2L,
                    r.base() ? "Bazowy" : "Firma",
                    r.base(),
                    tier,
                    r.rate(),
                    r.quantity(),
                    r.revenue()));
        }
        return new Bundle(99L, List.of(plan), approved, List.of(), BASE_TIER, "Bez kategorii");
    }

    private static WorkerTypeTier tierOf(FakeReport r) {
        return r.base() ? BASE_TIER : WorkerTypeTier.uplift(r.uplift());
    }

    private static MarginRowDto singleRow(MarginMatrixDto matrix) {
        for (MarginCategoryGroupDto group : matrix.categories()) {
            if (!group.rows().isEmpty()) {
                return group.rows().get(0);
            }
        }
        return null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // =============================================================================================
    // A minimal in-memory report with a unique id.
    // =============================================================================================

    record FakeReport(Long reportId, Long workerId, boolean base, BigDecimal rate,
                      BigDecimal quantity, BigDecimal revenue, BigDecimal uplift) {
    }

    // =============================================================================================
    // Generators
    // =============================================================================================

    @Provide
    Arbitrary<List<FakeReport>> reports() {
        // Distinct report ids so the detail map keys never collide.
        return report().list().ofMinSize(0).ofMaxSize(6).uniqueElements(FakeReport::reportId);
    }

    private Arbitrary<FakeReport> report() {
        Arbitrary<Long> reportId = Arbitraries.longs().between(1L, 1_000L);
        Arbitrary<Long> workerId = Arbitraries.longs().between(1L, 4L);
        Arbitrary<Boolean> base = Arbitraries.of(true, false);
        Arbitrary<BigDecimal> rate = Arbitraries.oneOf(positiveRate(), Arbitraries.just(BigDecimal.ZERO));
        Arbitrary<BigDecimal> quantity = Arbitraries.integers().between(0, 500)
                .map(i -> new BigDecimal(i).movePointLeft(1)); // 0.0 .. 50.0
        Arbitrary<BigDecimal> revenue = positiveRate();
        Arbitrary<BigDecimal> uplift = Arbitraries.integers().between(0, 200)
                .map(i -> new BigDecimal(i).movePointLeft(2)); // 0.00 .. 2.00
        return Combinators.combine(reportId, workerId, base, rate, quantity, revenue, uplift)
                .as(FakeReport::new);
    }

    private Arbitrary<BigDecimal> positiveRate() {
        return Arbitraries.integers().between(1, 100_000).map(i -> new BigDecimal(i).movePointLeft(2));
    }
}
