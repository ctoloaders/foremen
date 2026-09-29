package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.service.estimate.EstimateAssignmentService.EditKind;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;

/**
 * The batched Save request body for the Estimate tab
 * ({@code POST /api/estimates/project/{projectId}/assignments}, FOR-05-05 design §B4/§B6).
 *
 * <p>Carries the whole staged set for a project — cell edits AND apply/recompute-originated staged
 * edits — persisted in one transaction (R15.2, R15.7). Mirrors the frontend
 * {@code ApplyAssignmentsRequest{ edits: StagedEditPayload[] }}; each {@link StagedEditPayload} maps
 * one-to-one to the service {@link StagedEdit} record via {@link #toStagedEdits()}.
 *
 * @param edits the ordered staged set (may be empty ⇒ a no-op Save)
 */
public record ApplyAssignmentsRequest(List<StagedEditPayload> edits) {

    /** Maps the request's staged payloads to the service {@link StagedEdit}s, preserving order. */
    public List<StagedEdit> toStagedEdits() {
        if (edits == null || edits.isEmpty()) {
            return List.of();
        }
        List<StagedEdit> mapped = new ArrayList<>(edits.size());
        for (StagedEditPayload edit : edits) {
            if (edit != null) {
                mapped.add(edit.toStagedEdit());
            }
        }
        return mapped;
    }

    /**
     * One staged matrix edit in the batched Save (mirrors the frontend {@code StagedEditPayload} and
     * the service {@link StagedEdit}). A single flat shape covers every {@link EditKind}; only the
     * fields relevant to a given kind are populated.
     *
     * @param kind           the edit kind (its string mirrors {@link EditKind})
     * @param workItemId     target work (ASSIGN / UNASSIGN / APPLY_PACKAGE / BULK_CHOOSE_CONCRETE)
     * @param roomId         target room (ASSIGN / UNASSIGN / APPLY_PACKAGE)
     * @param roomQtyId      target cell for a material edit (ADD/REMOVE material)
     * @param branch         material branch for a material edit
     * @param typeId         material type id for a material edit
     * @param materialLineId target material line (CHOOSE_CONCRETE / RECOMPUTE_FINISHING)
     * @param materialId     chosen concrete product (CHOOSE_CONCRETE / BULK_CHOOSE_CONCRETE)
     * @param packageCode    active package code (ASSIGN / APPLY_PACKAGE / RECOMPUTE_FINISHING)
     * @param quantity       the manual Volume for a {@code SET_QUANTITY} cell override (FOR-05-05 #7),
     *                       or the manual physical quantity for a {@code SET_MATERIAL_QUANTITY} line
     *                       override (amendment #1); {@code null} for every other kind (including
     *                       {@code CLEAR_QUANTITY} / {@code CLEAR_MATERIAL_QUANTITY})
     */
    public record StagedEditPayload(
            EditKind kind,
            Long workItemId,
            Long roomId,
            Long roomQtyId,
            ConsumptionBranch branch,
            Long typeId,
            Long materialLineId,
            Long materialId,
            String packageCode,
            BigDecimal quantity) {

        /** Maps this request payload to the service {@link StagedEdit} record. */
        public StagedEdit toStagedEdit() {
            return new StagedEdit(
                    kind, workItemId, roomId, roomQtyId, branch, typeId, materialLineId, materialId,
                    packageCode, quantity);
        }
    }
}
