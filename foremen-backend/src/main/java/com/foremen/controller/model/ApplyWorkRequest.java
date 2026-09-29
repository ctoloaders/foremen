package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for the read-only work-row apply (hammer) calculate/preview endpoint
 * ({@code POST /api/estimates/project/{projectId}/apply-work}, FOR-05-05 design §B6).
 *
 * <p>Carries the single work to apply over its Room_Type_Attachment (all rooms when empty) and an
 * optional active package driving the override formula + finishing assortment placeholders. The
 * endpoint computes and returns the resulting matrix read model <b>without persisting anything</b>
 * (R9.5, R15.6); the client stages the returned result as undoable edits written only on Save.
 *
 * @param workItemId  the work to apply to its attached rooms (mandatory)
 * @param packageCode the active package code driving the override formula + assortment, or {@code null}
 */
public record ApplyWorkRequest(@NotNull Long workItemId, String packageCode) {}
