package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for the read-only Apply_Package calculate/preview endpoint
 * ({@code POST /api/estimates/project/{projectId}/apply-package}, FOR-05-05 design §B6).
 *
 * <p>Carries the single package to apply. The endpoint computes and returns the resulting matrix
 * read model <b>without persisting anything</b> (R11.2, R15.6); the client stages the returned
 * result as undoable {@code APPLY_PACKAGE} edits and it is written only on the batched Save.
 *
 * @param packageCode the offer-package code whose member works to apply
 */
public record ApplyPackageRequest(@NotBlank String packageCode) {}
