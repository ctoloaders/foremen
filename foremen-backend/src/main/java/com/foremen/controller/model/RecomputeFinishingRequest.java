package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for the read-only Recompute_Finishing_Prices calculate/preview endpoint
 * ({@code POST /api/estimates/project/{projectId}/recompute-finishing}, FOR-05-05 design §B6).
 *
 * <p>Carries the package whose finishing prices to recompute. The endpoint recomputes the
 * package-scoped finishing range for already-assigned finishing material lines only and returns the
 * resulting matrix read model <b>without persisting anything</b> (R12.2, R15.6); it touches neither
 * construction, labour, volumes, nor a chosen concrete product (R12.3, R12.4). The client stages the
 * returned result as undoable {@code RECOMPUTE_FINISHING} edits written only on Save.
 *
 * @param packageCode the offer-package code whose finishing prices to recompute
 */
public record RecomputeFinishingRequest(@NotBlank String packageCode) {}
