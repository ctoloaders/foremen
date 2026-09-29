package com.foremen.service.estimate.materials;

import java.math.BigDecimal;
import java.util.List;

/**
 * The full Materials tab read model (FOR-05-05b, design "Read model DTOs"). Returned by
 * {@code GET /api/estimates/project/{projectId}/materials} and mirrored by the frontend
 * {@code MaterialsListDto}.
 *
 * <p>It is a read-and-annotate projection over the kosztorys ({@code EstimateMatrixDto}): rows are the
 * distinct concrete materials partitioned by branch (R1), each with per-room cells and a
 * {@code Row_Total} (R2, R3); {@code branchDashboards} + {@code grandTotal} summarize consumption money
 * as-is vs effective in net + brutto (R6.2, R6.3); {@code fulfilmentPct} passes through the kosztorys
 * fill indicator verbatim (R6.5). {@code editable} folds project DRAFT ∧ caller-has-{@code ESTIMATE}-
 * UPDATE (resolved at the controller, R8.2, R10.3).
 *
 * @param projectId        the owning project id
 * @param editable         whether the reserve map may be written (project DRAFT + caller has UPDATE)
 * @param rooms            the room columns, in the kosztorys stable order (R2.1)
 * @param branches         the material rows grouped by branch (construction, finishing, R1.5)
 * @param branchDashboards the per-branch dashboard summaries (R6.2)
 * @param grandTotal       the project-wide dashboard grand total (R6.3)
 * @param fulfilmentPct    the kosztorys fill indicator, verbatim (R6.5)
 */
public record MaterialsListDto(
        Long projectId,
        boolean editable,
        List<MaterialsRoomColumnDto> rooms,
        List<MaterialBranchGroupDto> branches,
        List<BranchDashboardDto> branchDashboards,
        BranchDashboardDto grandTotal,
        BigDecimal fulfilmentPct) {
}
