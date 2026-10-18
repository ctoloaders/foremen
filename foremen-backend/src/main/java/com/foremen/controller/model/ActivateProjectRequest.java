package com.foremen.controller.model;

/**
 * Request body for the explicit project-activation endpoint
 * ({@code POST /api/projects/{id}/activate}, FOR-05-12 design §Backend design → Changed components →
 * ProjectController).
 *
 * <p>Carries the optional {@code force} flag (Requirements 1.5, 3). The endpoint declares the body
 * as {@code @RequestBody(required = false)}, so a request may omit the body entirely; when the body
 * (or the {@code force} field) is absent, the primitive {@code boolean} defaults to {@code false} on
 * JSON binding, i.e. a normal (non-forced) gated activation. {@code force = true} is honored only for
 * an ADMIN/MANAGER caller and bypasses only the readiness gate; see
 * {@code com.foremen.service.ProjectActivationService}.
 *
 * @param force when {@code true} (and the caller is ADMIN/MANAGER), bypass the readiness gate;
 *              {@code null}/absent is treated as {@code false}. Declared as a nullable
 *              {@link Boolean} (not a primitive) so an explicit {@code {}} or {@code {"force": null}}
 *              body binds cleanly instead of failing Jackson's {@code FAIL_ON_NULL_FOR_PRIMITIVES};
 *              the controller coerces {@code null} to {@code false} via {@link #forceOrFalse()}.
 */
public record ActivateProjectRequest(Boolean force) {

    /** The requested force flag, treating a {@code null}/absent value as {@code false}. */
    public boolean forceOrFalse() {
        return Boolean.TRUE.equals(force);
    }
}
