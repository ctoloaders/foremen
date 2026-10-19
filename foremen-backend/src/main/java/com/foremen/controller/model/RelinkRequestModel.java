package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

/**
 * FOR-06-04 (Requirement 2.1; design §5.3, §5.4) — the request body of the manual re-link endpoint
 * ({@code PUT /api/procurement-lines/{id}/match}).
 *
 * <p>Carries the chosen {@code Planned_Material} id the line should be re-linked to. The service
 * verifies the id is in the project's {@code PlannedMaterialCandidateProvider} candidate set (else
 * {@code error.purchases.relink.notPlanned}, line unchanged) before setting the line to
 * {@code MANUAL_MATCHED} / tier {@code EXACT}. Clearing a match is the parameterless
 * {@code DELETE /{id}/match} endpoint and takes no body.
 *
 * @param materialId the chosen planned-material id to re-link to (mandatory)
 */
public record RelinkRequestModel(@NotNull Long materialId) {
}
