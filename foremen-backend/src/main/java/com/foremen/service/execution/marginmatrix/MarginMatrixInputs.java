package com.foremen.service.execution.marginmatrix;

import java.math.BigDecimal;
import java.util.List;

import com.foremen.service.estimate.margins.WorkerTypeTier;

/**
 * FOR-06-06 (design §B1) — the pure, entity-free inputs the {@link MarginMatrixAssembler} consumes.
 *
 * <p>These records carry only the already-resolved, localized fields the fold needs, deliberately
 * decoupled from the JPA entities ({@code EstimateLineEntity}, {@code WorkReportEntity},
 * {@code ProcurementLineEntity}, {@code WorkerTypeEntity}, {@code ProjectMemberEntity}) so the
 * assembler stays a side-effect-free function directly testable with jqwik and no Spring context. The
 * {@code MarginMatrixService} (task 2.1) does the I/O — loading the estimate lines, the APPROVED work
 * reports, each report worker's resolved tier, the active {@code WorkerType} tiers, and the reconciled
 * procurement lines — and maps them to these value types before delegating to the assembler.
 */
public final class MarginMatrixInputs {

    private MarginMatrixInputs() {
        // namespace holder for the assembler's pure input value types
    }

    /**
     * One estimate work item on the plan side (R1.1, R1.2). Rows are produced one per distinct work
     * item on the estimate, in {@link #categoryOrder} then {@link #workItemId} order (R4.1).
     *
     * @param workItemId    the work item id
     * @param workItemName  the work item's localized name
     * @param categoryId    the owning work category id, or {@code null}
     * @param categoryName  the work category's localized name ({@code ru→pl→code})
     * @param categoryOrder a sort key preserving the estimate's category ordering ({@code null} last)
     * @param unit          the work item's unit code, or {@code null}
     * @param unitPrice     the frozen estimate unit price; {@code null}/zero ⇒ unavailable plan cost (R1.6)
     * @param quantity      the planned quantity
     * @param valueNet      the frozen {@code EstimateLine.valueNet} (plan revenue)
     */
    public record PlanLine(
            Long workItemId,
            String workItemName,
            Long categoryId,
            String categoryName,
            Integer categoryOrder,
            String unit,
            BigDecimal unitPrice,
            BigDecimal quantity,
            BigDecimal valueNet) {
    }

    /**
     * One APPROVED work report on the fact side, with its worker's tier already resolved (R1.3, R1.4,
     * R2.4, R2.5). The {@link #tier} is the {@code WorkerType} resolved via the worker's
     * {@code ProjectMember.workerType}, or the base tier when the worker is Uncategorized / inactive /
     * has no membership — in which case {@link #baseTierFallback} is {@code true} and
     * {@link #workerTypeId} is {@code null}.
     *
     * @param reportId         the approved work report id
     * @param workItemId       the reported work item id (joins to a {@link PlanLine})
     * @param workerId         the report's worker id
     * @param workerName       the worker's localized display name
     * @param workerTypeId     the resolved worker type id, or {@code null} on a base-tier fallback
     * @param workerTypeName   the resolved tier's localized name (the base tier's name on fallback)
     * @param baseTierFallback whether the tier fell back to the base tier (R2.5)
     * @param tier             the resolved {@link WorkerTypeTier} the fact cost is computed at
     * @param clientUnitPrice  the report's frozen client unit price; {@code null}/zero ⇒ unavailable cost
     * @param totalQuantity    the report's {@code totalQuantity}
     * @param revenueValue     the report's {@code revenueValue}
     */
    public record ApprovedReport(
            Long reportId,
            Long workItemId,
            Long workerId,
            String workerName,
            Long workerTypeId,
            String workerTypeName,
            boolean baseTierFallback,
            WorkerTypeTier tier,
            BigDecimal clientUnitPrice,
            BigDecimal totalQuantity,
            BigDecimal revenueValue) {
    }

    /**
     * One reconciled procurement line on the material + indirect fact side (R3.1, R3.2). A
     * {@link #linked} line (non-null {@code matchedMaterialId} and {@code matchStatus ∈ {AUTO,
     * MANUAL_MATCHED}}) sums into {@code Linked_Material_Cost}; a non-linked line sums into
     * {@code Indirect_Cost}, bucketed by {@link #unplannedCategoryId} / {@link #unplannedCategoryName}
     * (a {@code null} category id lands in the uncategorized bucket).
     *
     * @param linked               whether the line is a linked, bought material (R3.1)
     * @param lineTotal            the line's {@code lineTotal}; {@code null} contributes zero
     * @param unplannedCategoryId  the indirect-bucket category id, or {@code null} (uncategorized)
     * @param unplannedCategoryName the indirect-bucket category's localized name, or {@code null}
     */
    public record ProcurementLine(
            boolean linked,
            BigDecimal lineTotal,
            Long unplannedCategoryId,
            String unplannedCategoryName) {
    }

    /**
     * The full assembler input bundle: the plan lines, the approved reports, the procurement lines,
     * the base tier (for the plan-side and fallback cost), and the localized "uncategorized" label.
     *
     * @param projectId          the owning project id
     * @param planLines          the estimate work items (plan side)
     * @param approvedReports     the APPROVED work reports (fact side), with resolved tiers
     * @param procurementLines    the reconciled procurement lines (material + indirect fact)
     * @param baseTier            the base {@link WorkerTypeTier} (plan side + fallback), may be {@code null}
     * @param uncategorizedLabel  the localized name for the uncategorized indirect bucket
     */
    public record Bundle(
            Long projectId,
            List<PlanLine> planLines,
            List<ApprovedReport> approvedReports,
            List<ProcurementLine> procurementLines,
            WorkerTypeTier baseTier,
            String uncategorizedLabel) {
    }
}
