package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

/**
 * A single team-member entry of the {@link CreateProjectRequest} {@code members} array
 * (FOR-04-13 Requirement 2.4): the {@code userId} to assign to the created project and the
 * {@code projectRoleId} under which the member is assigned (by default the user's company role).
 *
 * <p>Both fields are mandatory; the surrounding {@code CreateProjectRequest} carries {@code @Valid}
 * on the collection so a null {@code userId}/{@code projectRoleId} is rejected with HTTP 400 before
 * any persistence.
 */
public record ProjectMemberInput(
        @NotNull Long userId,
        @NotNull Long projectRoleId
) {}
