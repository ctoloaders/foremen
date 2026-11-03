package com.foremen.service.workercost;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.WorkerTypeEntity;
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
import net.jqwik.api.Tuple;

/**
 * FOR-06-10 (design §Correctness Properties, Property 6; task 5.1) — property-based verification of the
 * <strong>FOR-06-06 parity (single formula)</strong>: for any approved report, the fact cost the
 * FOR-06-06 margin matrix produces equals {@code WorkerCostService}'s {@code Cost_Value(report)}
 * penny-for-penny, with the identical base-tier fallback (R4.1, R4.2, R4.3).
 *
 * <p>The {@link MarginMatrixAssembler} delegates its fact-side labour cost to the shared
 * {@link WorkerCostDerivation#costValue} seam (the one {@code MarginCostService} core). This property
 * drives the <b>real</b> assembler over a generated {@link Bundle} and asserts, for every per-report
 * detail row, that the matrix's {@code derivedTierCost} equals the worker-cost seam's per-report
 * {@code Cost_Value} — and that the row's {@code factCost} equals the sum of those per-report costs
 * (R4.4).
 *
 * <p><b>FOR-07-06 divergence (R2.1, R2.5).</b> {@code WorkerCostService} now resolves the tier from the
 * worker <em>user's</em> own {@code workerType} + {@code seniorityPct} (via {@link WorkerProfile} /
 * {@link EffectiveTier}), while the FOR-06-06 margin matrix deliberately keeps its
 * {@code ProjectMember.workerType} resolution (left untouched). The two therefore agree only when the
 * user's type equals the member's type and the seniority is 0 — parity is pinned <em>exactly
 * there</em>. Generated reports whose user profile diverges (a different user type, or
 * {@code seniorityPct > 0}) are expected to differ and are skipped by the parity assertions; the
 * row-sum check runs only when every report in the bundle is aligned.
 *
 * <p>Minimum 100 iterations each, per the design test strategy.
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.3</b> (FOR-06-10); FOR-07-06 Requirements 2.4, 2.5
 */
@Tag("Feature: FOR-06-10-worker-cost-rates, Property 6: FOR-06-06 parity (single formula)")
class WorkerCostFor0606ParityPropertyTest {

    private static final BigDecimal BASE_SHARE = new BigDecimal("0.40");
    private static final WorkerTypeTier BASE_TIER = WorkerTypeTier.base(BASE_SHARE);
    private static final WorkerTypeEntity BASE_TYPE = type(1L, BASE_SHARE, true);
    private static final Long WORK_ITEM_ID = 1L;

    private final MarginMatrixAssembler assembler = new MarginMatrixAssembler();

    // =============================================================================================
    // Property 6 — the matrix's per-report fact cost == the seam's Cost_Value (aligned profiles).
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
        boolean allAligned = true;
        for (FakeReport r : reports) {
            MarginReportDetailDto detail = detailByReportId.get(r.reportId());
            assertThat(detail)
                    .as("every approved report must appear as a matrix detail row (report %s)", r.reportId())
                    .isNotNull();
            if (!r.aligned()) {
                // FOR-07-06: user type != member type or seniority > 0 — the seam (user-based) and the
                // untouched margin matrix (member-based) legitimately diverge; parity is not pinned.
                allAligned = false;
                continue;
            }
            // The worker-cost seam exactly as WorkerCostService.costFor resolves it (the user's
            // profile through EffectiveTier), with the assembler's null-safe quantity (nz).
            BigDecimal serviceCost = serviceCost(r);
            if (serviceCost == null) {
                assertThat(detail.derivedTierCost())
                        .as("an unavailable-rate report is unavailable on both sides (report %s)", r.reportId())
                        .isNull();
            } else {
                assertThat(detail.derivedTierCost())
                        .as("matrix derivedTierCost == seam Cost_Value penny-for-penny "
                                + "(report %s, R4.1/R4.2)", r.reportId())
                        .isEqualByComparingTo(serviceCost);
                expectedFactCost = expectedFactCost.add(serviceCost);
            }
        }
        if (row != null && allAligned) {
            assertThat(row.factCost())
                    .as("the row fact cost equals the Σ of the seam's per-report costs (R4.4)")
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
            if (!r.aligned()) {
                continue; // FOR-07-06 divergence: parity pinned only for aligned profiles
            }
            // A base-tier (Uncategorized/fallback) report costs at the base tier on both sides.
            if (r.base()) {
                assertThat(detail.baseTierFallback())
                        .as("a base-tier report is flagged baseTierFallback on the matrix side (report %s)",
                                r.reportId())
                        .isTrue();
                assertThat(WorkerProfile.resolve(profileOf(r), BASE_TYPE).baseTierFallback())
                        .as("the seam flags the same report as a base-tier fallback (report %s)", r.reportId())
                        .isTrue();
                BigDecimal baseCost = serviceCost(r);
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

    /** The matrix (member-based) tier: the unchanged FOR-06-06 resolution. */
    private static WorkerTypeTier tierOf(FakeReport r) {
        return r.base() ? BASE_TIER : WorkerTypeTier.uplift(r.uplift());
    }

    /**
     * The worker user's profile the FOR-07-06 seam resolves from. Aligned: the user type equals the
     * member type (none for a base/fallback member) and seniority is 0. Diverged: the user carries a
     * different type ({@code uplift + 0.10}) and/or a positive seniority.
     */
    private static WorkerProfile profileOf(FakeReport r) {
        WorkerTypeEntity memberType = r.base() ? null : type(2L, r.uplift(), false);
        if (r.aligned()) {
            return new WorkerProfile(r.workerId(), memberType, BigDecimal.ZERO);
        }
        WorkerTypeEntity otherType = type(3L, r.uplift().add(new BigDecimal("0.10")), false);
        return new WorkerProfile(r.workerId(), r.userTypeDiffers() ? otherType : memberType, r.seniority());
    }

    /** The seam's per-report Cost_Value (what WorkerCostService.costFor computes). */
    private static BigDecimal serviceCost(FakeReport r) {
        WorkerTypeTier tier = WorkerProfile.resolve(profileOf(r), BASE_TYPE).tier();
        return WorkerCostDerivation.costValue(r.rate(), tier, BASE_TIER, nz(r.quantity()));
    }

    private static WorkerTypeEntity type(Long id, BigDecimal tierPct, boolean base) {
        WorkerTypeEntity t = new WorkerTypeEntity();
        t.setId(id);
        t.setTierPct(tierPct);
        t.setBase(base);
        t.setActive(true);
        t.setCode("WT" + id);
        return t;
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
    // A minimal in-memory report with a unique id + the worker user's (possibly divergent) profile.
    // =============================================================================================

    record FakeReport(Long reportId, Long workerId, boolean base, BigDecimal rate,
                      BigDecimal quantity, BigDecimal revenue, BigDecimal uplift, Divergence divergence) {
        boolean userTypeDiffers() {
            return divergence.userTypeDiffers();
        }

        BigDecimal seniority() {
            return divergence.seniority();
        }

        /** User type == member type and seniority == 0 — the only case parity is pinned for. */
        boolean aligned() {
            return !divergence.userTypeDiffers() && divergence.seniority().signum() == 0;
        }
    }

    /** FOR-07-06: how the worker user's own profile differs from the membership the matrix reads. */
    record Divergence(boolean userTypeDiffers, BigDecimal seniority) {
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
        return Combinators.combine(reportId, workerId, base, rate, quantity, revenue, uplift, divergence())
                .as(FakeReport::new);
    }

    /** Mostly aligned profiles (parity pinned); some diverge on user type and/or seniority. */
    private Arbitrary<Divergence> divergence() {
        Arbitrary<Boolean> userTypeDiffers = Arbitraries.frequency(Tuple.of(4, false), Tuple.of(1, true));
        Arbitrary<BigDecimal> seniority = Arbitraries.frequencyOf(
                Tuple.of(4, Arbitraries.just(BigDecimal.ZERO)),
                Tuple.of(1, Arbitraries.integers().between(1, 20)
                        .map(i -> new BigDecimal(i).movePointLeft(2)))); // 0.01 .. 0.20
        return Combinators.combine(userTypeDiffers, seniority).as(Divergence::new);
    }

    private Arbitrary<BigDecimal> positiveRate() {
        return Arbitraries.integers().between(1, 100_000).map(i -> new BigDecimal(i).movePointLeft(2));
    }
}
