package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The {@code POST /api/project-members} assign request.
 *
 * <p><b>FOR-05-09 (task 7.1, D2).</b> A member's Project_Role is always the user's current
 * Company_Role, resolved server-side — never taken from the request. Accordingly {@code projectRoleId}
 * is <em>optional</em>: when supplied it is only validated for a mismatch against the user's
 * Company_Role (400 {@code error.project.member.role.mismatch}), and is never the persisted role.
 * {@code workerTypeId} (WORKER members only) and the 0–10 free-text {@code tags} are optional
 * Assignment attributes applied at assign time (Requirement 5 criteria 2, 3).
 *
 * @param userId        the user to assign (mandatory); its Company_Role becomes the Project_Role
 * @param projectId     the project to assign to (mandatory)
 * @param projectRoleId optional supplied role; validated for mismatch only, never persisted (D2)
 * @param workerTypeId  optional worker type, applied only to a WORKER member
 * @param tags          optional 0–10 free-text tags, stored in normalized form
 */
public record AssignProjectMemberRequest(
        @NotNull Long userId,
        @NotNull Long projectId,
        Long projectRoleId,
        Long workerTypeId,
        List<String> tags
) {}
