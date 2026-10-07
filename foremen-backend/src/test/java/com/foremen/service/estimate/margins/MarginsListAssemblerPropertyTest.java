package com.foremen.service.estimate.margins;

// Feature: for-05-06-packages-margins

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.service.estimate.margins.MarginsListAssembler.MaterialCostLookup;
import com.foremen.service.estimate.matrix.BranchSubtotals;
import com.foremen.service.estimate.matrix.CellDto;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;
import com.foremen.service.estimate.matrix.FillState;
import com.foremen.service.estimate.matrix.MaterialLineDto;
import com.foremen.service.estimate.matrix.MoneyRange;
import com.foremen.service.estimate.matrix.WorkRowDto;
import com.foremen.service.estimate.matrix.WorkTypeGroupDto;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link MarginsListAssembler} (FOR-05-06) — the pure, stateless, read-only
 * projection over the shipped kosztorys read model ({@link EstimateMatrixDto}) into the Margins tab
 * read model ({@link MarginsListDto}).
 *
 * <p>The assembler is exercised through its package-visible {@code static} core
 * ({@link MarginsListAssembler#project}) with NO Spring context and NO database: kosztorys matrices
 * are generated as in-memory {@link EstimateMatrixDto}s built from multiple groups/rows/cells, with
 * some null-offer (unpriced) rows for exclusion and construction + finishing material lines carrying
 * random quantity / retail / cost. Each property recomputes its expectation with an INDEPENDENT
 * oracle rather than reusing the assembler's own logic.
 *
 * <p>Covers three properties from the design "Correctness Properties":
 * <ul>
 *   <li><b>Property 4</b> — labour min/avg/max across tiers: {@code minProfit} = margin at the
 *       max-cost tier, {@code maxProfit} = margin at the base/min-cost tier, {@code avgProfit} the
 *       mean; each tier margin = {@code offer − tierCost} (R4.6).</li>
 *   <li><b>Property 5</b> — construction and finishing material margins are computed independently
 *       per branch ({@code retail − cost} per branch) and never folded into the labour margin
 *       (R4.4).</li>
 *   <li><b>Property 9</b> — each per-tier dashboard total = Σ of per-row tier figures; per-branch
 *       material dashboard totals = Σ of per-row branch figures over priced/available rows
 *       (R5.3).</li>
 * </ul>
 */
class MarginsListAssemblerPropertyTest {

    private static final int AVG_SCALE = 6;

    // Bounded universes so concrete material ids collide across cells/rows and the folds are exercised.
    private static final Long[] CONSTRUCTION_MATERIAL_IDS = {10L, 11L, 12L};
    private static final Long[] FINISHING_MATERIAL_IDS = {20L, 21L, 22L};

    // A fixed, ordered tier dictionary (mirrors the seeded tiers: one base share + three uplifts).
    private static final List<WorkerTypeRefDto> TIERS = List.of(
            new WorkerTypeRefDto(1L, "Baza", true, new BigDecimal("0.40")),
            new WorkerTypeRefDto(2L, "Bez narzędzi", false, new BigDecimal("0.10")),
            new WorkerTypeRefDto(3L, "JDG", false, new BigDecimal("0.2650")),
            new WorkerTypeRefDto(4L, "Firma", false, new BigDecimal("0.65")));

    // =============================================================================================
    // Property 4: Labour min/avg/max across tiers.
    //   minProfit == margin at the max-cost tier, maxProfit == margin at the base (min-cost) tier,
    //   avgProfit == mean of the tier margins; each tier margin = offer − tierCost.
    // Validates: Requirements 4.6
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: for-05-06-packages-margins, Property 4: Labour min/avg/max across tiers")
    void labourMinAvgMaxAcrossTiers(@ForAll("matrices") EstimateMatrixDto matrix) {
        MaterialCostLookup costs = costLookup();
        MarginsListDto dto = MarginsListAssembler.project(matrix, TIERS, costs);

        for (MarginWorkGroupDto group : dto.groups()) {
            for (MarginRowDto row : group.rows()) {
                BigDecimal offer = row.offerPrice();

                if (offer == null) {
                    // Unpriced row ⇒ every profit field unavailable (R4.5).
                    assertThat(row.minProfit()).isEqualTo(MoneyMargin.UNAVAILABLE);
                    assertThat(row.avgProfit()).isEqualTo(MoneyMargin.UNAVAILABLE);
                    assertThat(row.maxProfit()).isEqualTo(MoneyMargin.UNAVAILABLE);
                    continue;
                }

                // ---- Oracle: independently recompute each tier's cost + labour margin. ----
                BigDecimal baseCost = oracleBaseCost(offer);
                assertThat(row.baseCost())
                        .as("row base cost = round(0.40 × offer to 0.5)")
                        .isEqualByComparingTo(baseCost);

                List<BigDecimal> tierCosts = new ArrayList<>();
                List<BigDecimal> tierMargins = new ArrayList<>();
                for (WorkerTypeRefDto tier : TIERS) {
                    BigDecimal cost = tier.base()
                            ? baseCost
                            : baseCost.multiply(BigDecimal.ONE.add(tier.tierPct()));
                    tierCosts.add(cost);
                    tierMargins.add(offer.subtract(cost));
                }

                // The emitted per-tier costs + labour margins match the oracle exactly (R4.6).
                assertThat(row.tierCosts()).hasSameSizeAs(TIERS);
                for (int i = 0; i < TIERS.size(); i++) {
                    TierCostDto tc = row.tierCosts().get(i);
                    assertThat(tc.cost())
                            .as("tier %d cost = base × (1 + uplift)", i)
                            .isEqualByComparingTo(tierCosts.get(i));
                    assertThat(tc.labourMargin().amount())
                            .as("tier %d labour margin = offer − tierCost", i)
                            .isEqualByComparingTo(tierMargins.get(i));
                }

                // maxProfit is the margin at the CHEAPEST cost tier (the base); minProfit at the
                // MOST EXPENSIVE cost tier — selected by cost, independent of ordering (R4.6).
                BigDecimal minCost = tierCosts.stream().min(BigDecimal::compareTo).orElseThrow();
                BigDecimal maxCost = tierCosts.stream().max(BigDecimal::compareTo).orElseThrow();
                BigDecimal expectedMaxProfit = offer.subtract(minCost); // cheapest cost ⇒ best profit
                BigDecimal expectedMinProfit = offer.subtract(maxCost); // dearest cost ⇒ worst profit

                assertThat(row.maxProfit().amount())
                        .as("maxProfit is the margin at the cheapest (base) tier")
                        .isEqualByComparingTo(expectedMaxProfit);
                assertThat(row.minProfit().amount())
                        .as("minProfit is the margin at the most expensive tier")
                        .isEqualByComparingTo(expectedMinProfit);

                // min ≤ avg ≤ max holds by construction.
                assertThat(row.minProfit().amount())
                        .as("min ≤ max")
                        .isLessThanOrEqualTo(row.maxProfit().amount());

                // avgProfit is the arithmetic mean of the tier margins, at the assembler's scale.
                BigDecimal sum = tierMargins.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal expectedAvg =
                        sum.divide(BigDecimal.valueOf(TIERS.size()), AVG_SCALE, RoundingMode.HALF_UP);
                assertThat(row.avgProfit().amount())
                        .as("avgProfit is the mean of the tier margins")
                        .isEqualByComparingTo(expectedAvg);
                assertThat(row.avgProfit().amount())
                        .as("min ≤ avg ≤ max")
                        .isBetween(row.minProfit().amount(), row.maxProfit().amount());
            }
        }
    }

    // =============================================================================================
    // Property 5: Material margin per branch, kept separate.
    //   Construction and finishing material margins reflect ONLY that branch's lines
    //   (retail − cost per branch); labour min/avg/max ignore materials entirely.
    // Validates: Requirements 4.4
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: for-05-06-packages-margins, Property 5: Material margin per branch, kept separate from labour")
    void materialMarginPerBranchKeptSeparate(@ForAll("matrices") EstimateMatrixDto matrix) {
        MaterialCostLookup costs = costLookup();
        MarginsListDto dto = MarginsListAssembler.project(matrix, TIERS, costs);

        // Index the emitted rows by the source work row (workItemId is unique per row in the generator).
        Map<Long, MarginRowDto> emitted = new HashMap<>();
        for (MarginWorkGroupDto group : dto.groups()) {
            for (MarginRowDto row : group.rows()) {
                emitted.put(row.workItemId(), row);
            }
        }

        for (WorkTypeGroupDto group : matrix.groups()) {
            for (WorkRowDto srcRow : group.rows()) {
                MarginRowDto row = emitted.get(srcRow.workItemId());
                assertThat(row).as("every source row is projected").isNotNull();

                assertBranch(row.construction(), oracleBranch(srcRow, ConsumptionBranch.construction, costs));
                assertBranch(row.finishing(), oracleBranch(srcRow, ConsumptionBranch.finishing, costs));

                // The construction branch reflects ONLY construction lines (and vice versa): swapping
                // the branch oracle must NOT match unless the two branches happen to be equal, which
                // we assert directly by recomputing each branch from its own lines above.

                // Labour margins ignore materials entirely: recompute min/avg/max from offer + tier
                // costs alone (NO material term) and confirm they match the emitted labour profit.
                // If the assembler had folded a branch material margin into labour, these material-
                // free oracles would diverge on any row with a non-zero material margin.
                BigDecimal offer = row.offerPrice();
                if (offer != null) {
                    BigDecimal baseCost = oracleBaseCost(offer);
                    List<BigDecimal> margins = new ArrayList<>();
                    for (WorkerTypeRefDto tier : TIERS) {
                        BigDecimal cost = tier.base()
                                ? baseCost
                                : baseCost.multiply(BigDecimal.ONE.add(tier.tierPct()));
                        margins.add(offer.subtract(cost)); // labour only — NO material term
                    }
                    assertThat(row.maxProfit().amount())
                            .as("labour maxProfit derives only from offer/tier costs, not materials")
                            .isEqualByComparingTo(margins.stream().max(BigDecimal::compareTo).orElseThrow());
                    assertThat(row.minProfit().amount())
                            .as("labour minProfit derives only from offer/tier costs, not materials")
                            .isEqualByComparingTo(margins.stream().min(BigDecimal::compareTo).orElseThrow());
                }
            }
        }
    }

    // =============================================================================================
    // Property 9: Dashboard = Σ rows.
    //   Each per-tier dashboard total (offer / cost / margin) = Σ of the per-row tier figures over
    //   priced rows; per-branch material dashboard totals = Σ of per-row branch figures.
    // Validates: Requirements 5.3
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: for-05-06-packages-margins, Property 9: Dashboard totals equal the sum of per-row figures")
    void dashboardEqualsSumOfRows(@ForAll("matrices") EstimateMatrixDto matrix) {
        MaterialCostLookup costs = costLookup();
        MarginsListDto dto = MarginsListAssembler.project(matrix, TIERS, costs);

        List<MarginRowDto> rows = new ArrayList<>();
        for (MarginWorkGroupDto group : dto.groups()) {
            rows.addAll(group.rows());
        }

        // ---- Per-tier labour totals = Σ over priced rows of (offer, tierCost). ----
        MarginsDashboardDto dash = dto.dashboard();
        assertThat(dash.labourByTier()).hasSameSizeAs(TIERS);
        for (int i = 0; i < TIERS.size(); i++) {
            BigDecimal expectedOffer = BigDecimal.ZERO;
            BigDecimal expectedCost = BigDecimal.ZERO;
            for (MarginRowDto row : rows) {
                if (row.offerPrice() == null || row.tierCosts() == null) {
                    continue; // unpriced excluded (R5.4)
                }
                TierCostDto tc = row.tierCosts().get(i);
                if (tc.cost() == null) {
                    continue;
                }
                expectedOffer = expectedOffer.add(row.offerPrice());
                expectedCost = expectedCost.add(tc.cost());
            }
            TierTotalDto total = dash.labourByTier().get(i);
            assertThat(total.offerTotal())
                    .as("tier %d offer total = Σ priced-row offers", i)
                    .isEqualByComparingTo(expectedOffer);
            assertThat(total.costTotal())
                    .as("tier %d cost total = Σ priced-row tier costs", i)
                    .isEqualByComparingTo(expectedCost);
            // Total margin = offerTotal − costTotal (labour, R5.3).
            assertThat(total.margin().amount())
                    .as("tier %d dashboard margin = offerTotal − costTotal", i)
                    .isEqualByComparingTo(expectedOffer.subtract(expectedCost));
        }

        // ---- Per-branch material totals = Σ over rows of the per-row branch retail/cost, with the
        // dashboard's own inclusion rule (only non-zero per-row branch figures contribute). ----
        assertBranchTotal(dash.constructionTotal(), rows, MarginRowDto::construction);
        assertBranchTotal(dash.finishingTotal(), rows, MarginRowDto::finishing);
    }

    // ---------------------------------------------------------------------------------------------
    // Oracles
    // ---------------------------------------------------------------------------------------------

    /** Base cost oracle: round(0.40 × offer to nearest 0.5, HALF_UP), scale 2. */
    private static BigDecimal oracleBaseCost(BigDecimal offer) {
        BigDecimal raw = offer.multiply(new BigDecimal("0.40"));
        BigDecimal steps = raw.divide(new BigDecimal("0.5"), 0, RoundingMode.HALF_UP);
        return steps.multiply(new BigDecimal("0.5")).setScale(2, RoundingMode.HALF_UP);
    }

    /** The oracle per-branch material figure for a source work row (retail / cost / anyRetail / anyCost). */
    private static BranchOracle oracleBranch(WorkRowDto row, ConsumptionBranch branch, MaterialCostLookup costs) {
        BigDecimal retail = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        boolean anyRetail = false;
        boolean anyCost = false;
        if (row.cells() != null) {
            for (CellDto cell : row.cells()) {
                if (cell == null || cell.materials() == null) {
                    continue;
                }
                for (MaterialLineDto line : cell.materials()) {
                    if (line == null || line.branch() != branch || line.concreteMaterialId() == null) {
                        continue; // placeholder or other branch (kept separate, R4.4)
                    }
                    BigDecimal qty = line.quantity() == null ? BigDecimal.ZERO : line.quantity();
                    if (line.concreteNet() != null) {
                        retail = retail.add(qty.multiply(line.concreteNet()));
                        anyRetail = true;
                    }
                    BigDecimal costNet = costs.costNet(branch, line.concreteMaterialId());
                    if (costNet != null) {
                        cost = cost.add(qty.multiply(costNet));
                        anyCost = true;
                    }
                }
            }
        }
        return new BranchOracle(retail, cost, anyRetail, anyCost);
    }

    private record BranchOracle(BigDecimal retail, BigDecimal cost, boolean anyRetail, boolean anyCost) {
    }

    /** Assert an emitted per-row branch DTO matches the independent branch oracle (R4.4). */
    private static void assertBranch(BranchMaterialDto actual, BranchOracle oracle) {
        if (!oracle.anyRetail() && !oracle.anyCost()) {
            // Nothing contributed ⇒ the neutral ZERO branch figure with an unavailable margin.
            assertThat(actual.retailTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(actual.costTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            return;
        }
        BigDecimal expectedRetail = oracle.anyRetail() ? oracle.retail() : BigDecimal.ZERO;
        BigDecimal expectedCost = oracle.anyCost() ? oracle.cost() : BigDecimal.ZERO;
        assertThat(actual.retailTotal())
                .as("branch retail = Σ qty × concreteNet over this branch's concrete lines")
                .isEqualByComparingTo(expectedRetail);
        assertThat(actual.costTotal())
                .as("branch cost = Σ qty × costNet over this branch's concrete lines")
                .isEqualByComparingTo(expectedCost);

        // Margin = retail − cost, computed only when both sides are available (R4.4, R5.4).
        if (oracle.anyRetail() && oracle.anyCost()) {
            assertThat(actual.margin().amount())
                    .as("branch margin = retail − cost (kept separate from labour)")
                    .isEqualByComparingTo(oracle.retail().subtract(oracle.cost()));
        } else {
            assertThat(actual.margin().amount())
                    .as("branch margin unavailable when one side missing")
                    .isNull();
        }
    }

    /** Assert a dashboard per-branch total equals Σ of per-row branch figures (dashboard inclusion rule). */
    private static void assertBranchTotal(
            BranchMaterialDto total,
            List<MarginRowDto> rows,
            java.util.function.Function<MarginRowDto, BranchMaterialDto> pick) {
        BigDecimal retail = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        boolean anyRetail = false;
        boolean anyCost = false;
        for (MarginRowDto row : rows) {
            BranchMaterialDto branch = pick.apply(row);
            if (branch == null) {
                continue;
            }
            // Dashboard folds only NON-ZERO per-row branch figures (mirrors BranchAcc.add).
            if (branch.retailTotal() != null && branch.retailTotal().signum() != 0) {
                retail = retail.add(branch.retailTotal());
                anyRetail = true;
            }
            if (branch.costTotal() != null && branch.costTotal().signum() != 0) {
                cost = cost.add(branch.costTotal());
                anyCost = true;
            }
        }
        BigDecimal expectedRetail = anyRetail ? retail : BigDecimal.ZERO;
        BigDecimal expectedCost = anyCost ? cost : BigDecimal.ZERO;
        assertThat(total.retailTotal())
                .as("dashboard branch retail = Σ per-row branch retail")
                .isEqualByComparingTo(expectedRetail);
        assertThat(total.costTotal())
                .as("dashboard branch cost = Σ per-row branch cost")
                .isEqualByComparingTo(expectedCost);
        if (anyRetail && anyCost) {
            assertThat(total.margin().amount())
                    .as("dashboard branch margin = retail − cost")
                    .isEqualByComparingTo(retail.subtract(cost));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A deterministic cost lookup: cost_net = retail-ish value derived from (branch, id). Some ids in
    // the finishing space return null to exercise unavailable-cost handling.
    // ---------------------------------------------------------------------------------------------

    private static MaterialCostLookup costLookup() {
        return (branch, id) -> {
            if (id == null) {
                return null;
            }
            // A single finishing id has no known cost ⇒ exercise the anyCost=false path.
            if (branch == ConsumptionBranch.finishing && id == 22L) {
                return null;
            }
            long base = branch == ConsumptionBranch.construction ? 3L : 5L;
            return new BigDecimal(base * id).setScale(2, RoundingMode.HALF_UP);
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Generators — randomized kosztorys matrices with multiple groups / rows / cells, some unpriced
    // rows, and construction + finishing material lines with random qty / retail / cost.
    // ---------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<EstimateMatrixDto> matrices() {
        Arbitrary<List<WorkTypeGroupDto>> groups =
                groupArbitrary().list().ofMinSize(1).ofMaxSize(3);
        return groups.map(gs -> {
            // Re-number work item ids across groups so every projected row has a unique key (used by
            // Property 5's index) while ids still repeat within a group's cells for material folds.
            List<WorkTypeGroupDto> renumbered = new ArrayList<>();
            long nextWorkId = 1L;
            long nextCatId = 1L;
            for (WorkTypeGroupDto g : gs) {
                List<WorkRowDto> rows = new ArrayList<>();
                for (WorkRowDto r : g.rows()) {
                    long wid = nextWorkId++;
                    List<CellDto> cells = new ArrayList<>();
                    for (CellDto c : r.cells()) {
                        cells.add(new CellDto(
                                wid, c.roomId(), c.assigned(), c.volume(), c.formulaUsed(),
                                c.formulaKey(), c.fallbackUsed(), c.volumeOverridden(), c.labour(),
                                c.materials(), c.costRange(), c.fillState()));
                    }
                    rows.add(new WorkRowDto(wid, "work-" + wid, List.of(), cells,
                            BigDecimal.ZERO, MoneyRange.ZERO));
                }
                long cid = nextCatId++;
                renumbered.add(new WorkTypeGroupDto(cid, "cat-" + cid, rows,
                        BranchSubtotals.ZERO, BigDecimal.ZERO, MoneyRange.ZERO));
            }
            return new EstimateMatrixDto(
                    500L, true, List.of(), renumbered,
                    BranchSubtotals.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, MoneyRange.ZERO, null);
        });
    }

    private Arbitrary<WorkTypeGroupDto> groupArbitrary() {
        Arbitrary<List<WorkRowDto>> rows = rowArbitrary().list().ofMinSize(1).ofMaxSize(3);
        return rows.map(rs -> new WorkTypeGroupDto(
                0L, "cat", rs, BranchSubtotals.ZERO, BigDecimal.ZERO, MoneyRange.ZERO));
    }

    private Arbitrary<WorkRowDto> rowArbitrary() {
        Arbitrary<List<CellDto>> cells = cellArbitrary().list().ofMinSize(1).ofMaxSize(3);
        return cells.map(cs -> new WorkRowDto(
                0L, "work", List.of(), cs, BigDecimal.ZERO, MoneyRange.ZERO));
    }

    private Arbitrary<CellDto> cellArbitrary() {
        // ~25% of cells are unassigned / unpriced so some rows end up with a null offer (excluded).
        Arbitrary<Boolean> assigned = Arbitraries.of(true, true, true, false);
        Arbitrary<Long> roomId = Arbitraries.of(100L, 101L, 102L);
        // labour: sometimes 0 or null so a row can sum to a null offer.
        Arbitrary<BigDecimal> labour = Arbitraries.oneOf(
                Arbitraries.bigDecimals().between(new BigDecimal("0"), new BigDecimal("5000"))
                        .ofScale(2),
                Arbitraries.just(null));
        Arbitrary<List<MaterialLineDto>> materials =
                materialLineArbitrary().list().ofMinSize(0).ofMaxSize(4);

        return Combinators.combine(assigned, roomId, labour, materials).as(
                (asg, room, lab, mats) -> new CellDto(
                        0L, room, asg, BigDecimal.ONE, "floorArea", "floorArea", false, false,
                        asg ? lab : null, mats, MoneyRange.ZERO, FillState.filled));
    }

    private Arbitrary<MaterialLineDto> materialLineArbitrary() {
        Arbitrary<ConsumptionBranch> branch = Arbitraries.of(
                ConsumptionBranch.construction, ConsumptionBranch.finishing);
        Arbitrary<BigDecimal> qty = Arbitraries.bigDecimals()
                .between(new BigDecimal("0"), new BigDecimal("50")).ofScale(4);
        // retail (concreteNet): sometimes null to exercise the anyRetail=false path.
        Arbitrary<BigDecimal> retail = Arbitraries.oneOf(
                Arbitraries.bigDecimals().between(new BigDecimal("0"), new BigDecimal("500"))
                        .ofScale(2),
                Arbitraries.just(null));
        // ~20% Placeholder lines (null concreteMaterialId) that must never contribute (R4.4).
        Arbitrary<Boolean> concrete = Arbitraries.of(true, true, true, true, false);

        return Combinators.combine(branch, qty, retail, concrete).as((br, q, ret, isConcrete) -> {
            Long concreteId = null;
            if (isConcrete) {
                Long[] pool = br == ConsumptionBranch.construction
                        ? CONSTRUCTION_MATERIAL_IDS
                        : FINISHING_MATERIAL_IDS;
                concreteId = pool[Math.abs(q.unscaledValue().intValue()) % pool.length];
            }
            return new MaterialLineDto(
                    0L, br, 999L, "type",
                    BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                    concreteId, concreteId == null ? null : "mat-" + concreteId,
                    concreteId == null ? null : ret,
                    "szt", q, false, ConsumptionBasis.PER_UNIT, false, null, false);
        });
    }
}
