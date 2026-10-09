package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

/**
 * A single team-member entry of the {@link CreateProjectRequest} {@code members} array
 * (FOR-04-13 Requirement 2.4): the {@code userId} to assign to the created project and the
 * {@code projectRoleId} under which the member is assigned (by default the user's company role).
 *
 * <p>{@code userId} and {@code projectRoleId} are mandatory; the surrounding
 * {@code CreateProjectRequest} carries {@code @Valid} on the collection so a null
 * {@code userId}/{@code projectRoleId} is rejected with HTTP 400 before any persistence.
 *
 * <p><b>FOR-05-09 (task 15.1, Requirement 26 criterion 3).</b> The entry contract accepts an
 * optional {@code workerTypeId} so that the Project_Creation_Orchestrator can <em>reject</em> it with
 * HTTP 400 {@code error.project.member.worker.type.not.allowed} rather than silently ignoring the
 * value — workers (and their worker type) are added only from the Team tab after the project exists
 * (D13). It carries no {@code @NotNull}: an absent / null {@code workerTypeId} is the normal case
 * (an admin-staff or client member has no worker type at creation), and any non-null value is
 * refused by the orchestrator without a worker-type lookup. The field is <em>not</em> bean-validated
 * as a positive integer here, because the orchestrator rejects every non-null value regardless of
 * whether it would be a valid worker-type id (Requirement 26 criterion 3).
 */
public record ProjectMemberInput(
        @NotNull Long userId,
        @NotNull Long projectRoleId,
        Long workerTypeId
) {

    /**
     * Backward-compatible two-argument constructor preserving the pre-FOR-05-09 contract (no
     * {@code workerTypeId}). Equivalent to supplying a {@code null} {@code workerTypeId} — the
     * no-worker-type case the Project_Creation_Orchestrator accepts.
     */
    public ProjectMemberInput(Long userId, Long projectRoleId) {
        this(userId, projectRoleId, null);
    }
}
