package com.foremen.service.estimate.matrix;

import java.math.BigDecimal;
import java.util.List;

/**
 * The full Estimate tab read model (FOR-05-05, design §B6 and "Frontend read model (mirrors backend
 * DTOs)"). Returned by {@code GET /api/estimates/project/{projectId}/matrix} and mirrored by the
 * frontend {@code EstimateMatrixDto} in {@code features/estimate/types/index.ts}.
 *
 * <p>Money is carried as net PLN {@link BigDecimal}s. A {@link MoneyRange} whose {@code min} equals
 * {@code max} is a <em>collapsed</em> (single-value) range — the convention used throughout: a cell /
 * subtotal / total collapses when every contributing material line has a concrete product chosen
 * (R4.3, R6.4, R14.2).
 *
 * <p>{@code editable} reflects the server-side view of whether the estimate may be written (project
 * DRAFT + caller has {@code ESTIMATE} UPDATE); the client gates on the same conditions (R15.5).
 * {@code totals} are the three header totals (each collapsing per R14.2) and {@code fillIndicatorPct}
 * is the share of assigned material lines with a concrete product chosen, in percent (R14.3).
 *
 * @param projectId        the owning project id
 * @param editable         whether the estimate may be written (DRAFT + caller has ESTIMATE UPDATE)
 * @param rooms            the room columns of the matrix (R1.1)
 * @param groups           the work rows grouped by work category (R2.1)
 * @param totals           the three header totals: works / construction / finishing (R14.1, R14.2)
 * @param fillIndicatorPct the concrete-line share as a percentage 0..100 (R14.3)
 */
public record EstimateMatrixDto(
        Long projectId,
        boolean editable,
        List<EstimateMatrixRoomDto> rooms,
        List<WorkTypeGroupDto> groups,
        BranchSubtotals totals,
        BigDecimal fillIndicatorPct) {
}
