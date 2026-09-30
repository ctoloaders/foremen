package com.foremen.service.estimate.margins;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.service.estimate.matrix.CellDto;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;
import com.foremen.service.estimate.matrix.MaterialLineDto;
import com.foremen.service.estimate.matrix.WorkRowDto;
import com.foremen.service.estimate.matrix.WorkTypeGroupDto;

/**
 * FOR-05-06 (design §B4) — the Margins tab read-only projection. A pure, stateless {@link Component}
 * that builds a {@link MarginsListDto} from the shipped kosztorys read model (the
 * {@link EstimateMatrixDto} produced by {@code EstimateMatrixAssembler}) plus the ordered WorkerType
 * tiers ({@link WorkerTypeRefDto}) and a material {@code cost_net} lookup. It performs no I/O beyond
 * reading the passed-in graph; every offer, cost, and margin is <strong>derived</strong> from that
 * graph and the pure {@link MarginCostService} core — there is no second cost formula (R2.3).
 *
 * <p>The projection walks every work row (grouped by work type, in the kosztorys category order,
 * R4.2/R4.7) and folds, per row: the offer service price (Σ of the row's cell labour), the
 * {@code Base_Cost}, one {@link TierCostDto} per WorkerType tier with its labour margin, the labour
 * min/avg/max profit across tiers (min at the most-expensive tier, max at the base tier, avg the mean,
 * R4.6), and the per-branch material retail/cost/margin (construction, finishing) kept separate from
 * the labour margin (decision #4, R4.4). Rows with a null offer are unavailable and excluded from the
 * dashboard folds (R4.5, R5.4). The dashboard folds the per-tier labour totals and the per-branch
 * material totals = Σ rows (Property 9).
 *
 * <p>The whole fold lives in pure {@code static} helpers so it is property-testable without Spring
 * (mirrors {@code MaterialsListAssembler}).
 */
@Component
public class MarginsListAssembler {

    /** Scale for the averaged labour-profit percentage / amount (matches the cost core's PCT scale). */
    private static final int AVG_SCALE = 6;

    /**
     * A material self-cost ({@code cost_net}) lookup keyed by {@code (branch, concreteMaterialId)}. A
     * {@code construction_materials.id} and a {@code finishing_materials.id} can share the same numeric
     * value, so the branch must be part of the key (mirrors {@code MaterialsListAssembler.MaterialKey}).
     * Returns {@code null} when no cost is known for the material (⇒ that line's cost is excluded, R5.4).
     */
    @FunctionalInterface
    public interface MaterialCostLookup {
        BigDecimal costNet(ConsumptionBranch branch, Long concreteMaterialId);
    }

    /**
     * Assemble the Margins tab read model from the kosztorys matrix, the ordered WorkerType tiers, and
     * the material self-cost lookup.
     *
     * @param matrix      the kosztorys read model (source of the works, offer labour, chosen materials
     *                    + retail nets); may be {@code null} for a pre-estimate project (⇒ empty model)
     * @param workerTypes the ordered WorkerType tiers (column headers); {@code null} ⇒ none
     * @param costLookup  the material {@code cost_net} lookup; {@code null} ⇒ all costs unavailable
     * @return the assembled {@link MarginsListDto}
     */
    public MarginsListDto assemble(
            EstimateMatrixDto matrix,
            List<WorkerTypeRefDto> workerTypes,
            MaterialCostLookup costLookup) {
        return project(matrix, workerTypes, costLookup);
    }

    // ---------------------------------------------------------------------------------------------
    // Pure projection core — property-testable without Spring
    // ---------------------------------------------------------------------------------------------

    /**
     * The pure projection over the kosztorys matrix (R4.2–R4.7, R5.3, R5.4, R2.3). Package-visible
     * {@code static} so the property tests can exercise it directly without a Spring context.
     */
    static MarginsListDto project(
            EstimateMatrixDto matrix,
            List<WorkerTypeRefDto> workerTypes,
            MaterialCostLookup costLookup) {

        Long projectId = matrix == null ? null : matrix.projectId();
        List<WorkerTypeRefDto> tiers = workerTypes == null ? List.of() : workerTypes;
        MaterialCostLookup costs = costLookup == null ? (b, id) -> null : costLookup;

        List<WorkerTypeTier> tierParams = toTierParams(tiers);
        WorkerTypeTier baseTier = baseTier(tierParams);

        List<MarginWorkGroupDto> groups = new ArrayList<>();
        if (matrix != null && matrix.groups() != null) {
            for (WorkTypeGroupDto group : matrix.groups()) {
                if (group == null) {
                    continue;
                }
                groups.add(assembleGroup(group, tiers, tierParams, baseTier, costs));
            }
        }

        MarginsDashboardDto dashboard = dashboard(groups, tiers);
        return new MarginsListDto(projectId, tiers, groups, dashboard);
    }

    /** Build one work-type group: the group identity + one {@link MarginRowDto} per work row (R4.7). */
    private static MarginWorkGroupDto assembleGroup(
            WorkTypeGroupDto group,
            List<WorkerTypeRefDto> tiers,
            List<WorkerTypeTier> tierParams,
            WorkerTypeTier baseTier,
            MaterialCostLookup costs) {
        List<MarginRowDto> rows = new ArrayList<>();
        if (group.rows() != null) {
            for (WorkRowDto row : group.rows()) {
                if (row == null) {
                    continue;
                }
                rows.add(assembleRow(
                        row, group.workCategoryId(), group.workCategoryName(),
                        tiers, tierParams, baseTier, costs));
            }
        }
        return new MarginWorkGroupDto(group.workCategoryId(), group.workCategoryName(), rows);
    }

    /**
     * Build one margin row: the offer (Σ cell labour), base cost, per-tier costs + labour margins,
     * labour min/avg/max profit, and the per-branch material figures (R4.3, R4.4, R4.6).
     */
    private static MarginRowDto assembleRow(
            WorkRowDto row,
            Long categoryId,
            String categoryName,
            List<WorkerTypeRefDto> tiers,
            List<WorkerTypeTier> tierParams,
            WorkerTypeTier baseTier,
            MaterialCostLookup costs) {

        BigDecimal offer = offerPrice(row);
        BigDecimal baseCost = MarginCostService.baseCost(offer, baseTier);

        List<TierCostDto> tierCosts = new ArrayList<>();
        for (int i = 0; i < tiers.size(); i++) {
            WorkerTypeRefDto ref = tiers.get(i);
            WorkerTypeTier param = tierParams.get(i);
            BigDecimal cost = MarginCostService.tierCost(baseCost, param);
            MoneyMargin labourMargin = MoneyMargin.of(MarginCostService.labourMargin(offer, cost));
            tierCosts.add(new TierCostDto(ref.id(), ref.name(), ref.base(), cost, labourMargin));
        }

        LabourProfit profit = labourProfit(offer, tierCosts);

        BranchMaterialDto construction = branchMaterial(row, ConsumptionBranch.construction, costs);
        BranchMaterialDto finishing = branchMaterial(row, ConsumptionBranch.finishing, costs);

        return new MarginRowDto(
                row.workItemId(), row.workItemName(), categoryId, categoryName,
                offer, baseCost, tierCosts,
                profit.min(), profit.avg(), profit.max(),
                construction, finishing);
    }

    // ---------------------------------------------------------------------------------------------
    // Offer price — Σ of the row's cell labour (unitPrice × Volume), null when the row is unpriced
    // ---------------------------------------------------------------------------------------------

    /**
     * The offer service price for a work row = Σ of the row's assigned cells' {@code labour}
     * ({@code unitPrice × Volume}). A row with no assigned/priced cells yields {@code null}
     * (unavailable) so the cost/margin cells render {@code —} and the row is excluded from totals
     * (R4.5). A row that is assigned but sums to a zero offer also yields {@code null} — a zero offer
     * is unavailable per the cost core (R2.4).
     */
    private static BigDecimal offerPrice(WorkRowDto row) {
        if (row.cells() == null) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        boolean any = false;
        for (CellDto cell : row.cells()) {
            if (cell == null || !cell.assigned() || cell.labour() == null) {
                continue;
            }
            sum = sum.add(cell.labour());
            any = true;
        }
        if (!any || sum.signum() == 0) {
            return null; // unpriced / zero offer ⇒ unavailable (R4.5, R2.4)
        }
        return sum;
    }

    // ---------------------------------------------------------------------------------------------
    // Labour min/avg/max profit across tiers (R4.6)
    // ---------------------------------------------------------------------------------------------

    /**
     * The labour profitability across tiers (R4.6): {@code max} margin at the base tier (cheapest
     * cost), {@code min} margin at the most expensive tier, {@code avg} the mean of the tier margins.
     * Any unavailable offer (⇒ every tier margin unavailable) yields all-unavailable profit. The
     * min/max are selected by the tier <em>cost</em> (min profit ↔ max cost) so the result is
     * independent of tier ordering.
     */
    private static LabourProfit labourProfit(BigDecimal offer, List<TierCostDto> tierCosts) {
        if (offer == null || tierCosts.isEmpty()) {
            return LabourProfit.UNAVAILABLE;
        }
        ProfitAcc acc = new ProfitAcc();
        for (TierCostDto tc : tierCosts) {
            acc.add(tc);
        }
        return acc.result();
    }

    /** The labour min/avg/max profit triple for a row (R4.6). */
    private record LabourProfit(MoneyMargin min, MoneyMargin avg, MoneyMargin max) {
        private static final LabourProfit UNAVAILABLE =
                new LabourProfit(MoneyMargin.UNAVAILABLE, MoneyMargin.UNAVAILABLE, MoneyMargin.UNAVAILABLE);
    }

    /**
     * Folds the available tier margins into the labour min/avg/max profit (R4.6): {@code max} tracks
     * the cheapest-cost tier (base), {@code min} the most-expensive-cost tier, {@code avg} the mean.
     * A tier with an unavailable margin or cost is skipped.
     */
    private static final class ProfitAcc {
        private MoneyMargin min;
        private MoneyMargin max;
        private BigDecimal minCost;
        private BigDecimal maxCost;
        private BigDecimal sumAmount = BigDecimal.ZERO;
        private BigDecimal sumPct = BigDecimal.ZERO;
        private int count;
        private boolean anyPct;

        private void add(TierCostDto tc) {
            MoneyMargin m = tc.labourMargin();
            if (m == null || m.amount() == null || tc.cost() == null) {
                return; // an unavailable tier does not contribute to min/avg/max
            }
            count++;
            sumAmount = sumAmount.add(m.amount());
            if (m.pct() != null) {
                sumPct = sumPct.add(m.pct());
                anyPct = true;
            }
            if (minCost == null || tc.cost().compareTo(minCost) < 0) {
                minCost = tc.cost();
                max = m; // cheapest cost ⇒ highest profit (base tier)
            }
            if (maxCost == null || tc.cost().compareTo(maxCost) > 0) {
                maxCost = tc.cost();
                min = m; // most expensive cost ⇒ lowest profit
            }
        }

        private LabourProfit result() {
            if (count == 0) {
                return LabourProfit.UNAVAILABLE;
            }
            BigDecimal divisor = BigDecimal.valueOf(count);
            BigDecimal avgAmount = sumAmount.divide(divisor, AVG_SCALE, RoundingMode.HALF_UP);
            BigDecimal avgPct = anyPct ? sumPct.divide(divisor, AVG_SCALE, RoundingMode.HALF_UP) : null;
            return new LabourProfit(min, new MoneyMargin(avgAmount, avgPct), max);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Per-branch material retail / cost / margin (R4.4, kept separate from labour)
    // ---------------------------------------------------------------------------------------------

    /**
     * The per-branch material figures for a work row: Σ over the row's concrete material lines in the
     * branch of {@code quantity × retailNet} (retail) and {@code quantity × cost_net} (cost), then the
     * material margin ({@code retail − cost}, R4.4). Placeholder lines (no concrete product) and lines
     * with a null retail/cost contribute nothing (no fabricated value, R5.4). When nothing contributes
     * the branch figure is {@link BranchMaterialDto#ZERO} with an unavailable margin.
     */
    private static BranchMaterialDto branchMaterial(
            WorkRowDto row, ConsumptionBranch branch, MaterialCostLookup costs) {
        MaterialAcc acc = new MaterialAcc();
        if (row.cells() != null) {
            for (CellDto cell : row.cells()) {
                acc.addCell(cell, branch, costs);
            }
        }
        return acc.toDto();
    }

    /**
     * Per-branch material retail/cost accumulator for a single work row. Folds only concrete lines in
     * the target branch; a line with a null retail/cost contributes nothing to that side (R5.4).
     */
    private static final class MaterialAcc {
        private BigDecimal retail = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private boolean anyRetail;
        private boolean anyCost;

        private void addCell(CellDto cell, ConsumptionBranch branch, MaterialCostLookup costs) {
            if (cell == null || cell.materials() == null) {
                return;
            }
            for (MaterialLineDto line : cell.materials()) {
                addLine(line, branch, costs);
            }
        }

        private void addLine(MaterialLineDto line, ConsumptionBranch branch, MaterialCostLookup costs) {
            if (line == null || line.branch() != branch || line.concreteMaterialId() == null) {
                return; // Placeholder or other branch (R5.4)
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

        private BranchMaterialDto toDto() {
            if (!anyRetail && !anyCost) {
                return BranchMaterialDto.ZERO;
            }
            BigDecimal r = anyRetail ? retail : null;
            BigDecimal c = anyCost ? cost : null;
            MoneyMargin margin = MoneyMargin.of(MarginCostService.materialMargin(r, c));
            return new BranchMaterialDto(
                    r == null ? BigDecimal.ZERO : r,
                    c == null ? BigDecimal.ZERO : c,
                    margin);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Dashboard folds — per-tier labour totals + per-branch material totals = Σ rows (Property 9)
    // ---------------------------------------------------------------------------------------------

    /**
     * Fold the dashboard from the assembled groups (Property 9, R5.2, R5.3): per tier, Σ over priced
     * rows of the offer and the tier cost (⇒ total labour margin); per branch, Σ over rows of the
     * material retail/cost (⇒ total material margin). Rows with an unavailable offer are excluded from
     * the labour folds; a branch line with no retail/cost is excluded from the material folds (R5.4).
     */
    private static MarginsDashboardDto dashboard(List<MarginWorkGroupDto> groups, List<WorkerTypeRefDto> tiers) {
        int tierCount = tiers.size();
        BigDecimal[] offerByTier = new BigDecimal[tierCount];
        BigDecimal[] costByTier = new BigDecimal[tierCount];
        for (int i = 0; i < tierCount; i++) {
            offerByTier[i] = BigDecimal.ZERO;
            costByTier[i] = BigDecimal.ZERO;
        }

        BranchAcc construction = new BranchAcc();
        BranchAcc finishing = new BranchAcc();

        for (MarginWorkGroupDto group : groups) {
            if (group.rows() == null) {
                continue;
            }
            for (MarginRowDto row : group.rows()) {
                foldLabour(row, offerByTier, costByTier);
                construction.add(row.construction());
                finishing.add(row.finishing());
            }
        }

        List<TierTotalDto> labourByTier = new ArrayList<>();
        for (int i = 0; i < tierCount; i++) {
            WorkerTypeRefDto ref = tiers.get(i);
            MoneyMargin margin = MoneyMargin.of(
                    MarginCostService.labourMargin(offerByTier[i], costByTier[i]));
            labourByTier.add(new TierTotalDto(
                    ref.id(), ref.name(), ref.base(), offerByTier[i], costByTier[i], margin));
        }

        return new MarginsDashboardDto(labourByTier, construction.toDto(), finishing.toDto());
    }

    /** Add a priced row's per-tier offer/cost into the dashboard accumulators (unavailable ⇒ skipped). */
    private static void foldLabour(MarginRowDto row, BigDecimal[] offerByTier, BigDecimal[] costByTier) {
        if (row.offerPrice() == null || row.tierCosts() == null) {
            return; // unpriced row excluded from labour totals (R5.4)
        }
        List<TierCostDto> tierCosts = row.tierCosts();
        for (int i = 0; i < tierCosts.size() && i < offerByTier.length; i++) {
            TierCostDto tc = tierCosts.get(i);
            if (tc.cost() == null) {
                continue;
            }
            offerByTier[i] = offerByTier[i].add(row.offerPrice());
            costByTier[i] = costByTier[i].add(tc.cost());
        }
    }

    /** A per-branch material totals accumulator for the dashboard (retail / cost). */
    private static final class BranchAcc {
        private BigDecimal retail = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private boolean anyRetail;
        private boolean anyCost;

        private void add(BranchMaterialDto branch) {
            if (branch == null) {
                return;
            }
            if (branch.retailTotal() != null && branch.retailTotal().signum() != 0) {
                retail = retail.add(branch.retailTotal());
                anyRetail = true;
            }
            if (branch.costTotal() != null && branch.costTotal().signum() != 0) {
                cost = cost.add(branch.costTotal());
                anyCost = true;
            }
        }

        private BranchMaterialDto toDto() {
            if (!anyRetail && !anyCost) {
                return BranchMaterialDto.ZERO;
            }
            BigDecimal r = anyRetail ? retail : null;
            BigDecimal c = anyCost ? cost : null;
            MoneyMargin margin = MoneyMargin.of(MarginCostService.materialMargin(r, c));
            return new BranchMaterialDto(
                    r == null ? BigDecimal.ZERO : r,
                    c == null ? BigDecimal.ZERO : c,
                    margin);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tier helpers
    // ---------------------------------------------------------------------------------------------

    /** Map each {@link WorkerTypeRefDto} to the pure {@link WorkerTypeTier} the cost core consumes. */
    private static List<WorkerTypeTier> toTierParams(List<WorkerTypeRefDto> tiers) {
        List<WorkerTypeTier> params = new ArrayList<>();
        for (WorkerTypeRefDto ref : tiers) {
            params.add(new WorkerTypeTier(ref.base(), ref.tierPct()));
        }
        return params;
    }

    /** The base tier (its {@code tierPct} is the offer share), or {@code null} when none is flagged. */
    private static WorkerTypeTier baseTier(List<WorkerTypeTier> tierParams) {
        for (WorkerTypeTier tier : tierParams) {
            if (tier.base()) {
                return tier;
            }
        }
        return null;
    }
}
