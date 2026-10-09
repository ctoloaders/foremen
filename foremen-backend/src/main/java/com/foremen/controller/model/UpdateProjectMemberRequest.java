package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The {@code PATCH /api/project-members} Attribute_Update request (FOR-05-09 Requirement 2
 * criterion 1, design §"Attribute_Update dispatch"). The single PATCH endpoint carries the three
 * mutually-exclusive UPDATE attribute groups the requirements describe; the service dispatches on
 * which field is present:
 *
 * <ul>
 *   <li>{@code assignmentStatus} present &rarr; deactivate / reactivate (Requirement 27 — owned by
 *       task 8.2);</li>
 *   <li>{@code workerTypeId} present (and no {@code assignmentStatus}) &rarr; worker-type
 *       set / replace (Requirement 14 — added by task 9.1);</li>
 *   <li>{@code tags} present (and neither of the above) &rarr; tag replace (Requirement 15 — added
 *       by task 9.5).</li>
 * </ul>
 *
 * <p><b>Project id in the body, never the path (Requirement 2 criteria 5, 6).</b> The target is the
 * {@code (userId, projectId)} pair carried in this body; no project identifier leaks into the URL.
 *
 * <p><b>Extension note (tasks 9.1 / 9.5).</b> The {@code workerTypeId} and {@code tags} fields are
 * present now so the request shape is stable, but <em>this task (8.2) acts only on
 * {@code assignmentStatus}</em>; the worker-type and tag dispatch branches are wired by tasks 9.1
 * and 9.5 without reworking this record.
 *
 * <p><b>{@code assignmentStatus} is a raw string on purpose.</b> It is carried as a {@code String}
 * (not the {@code AssignmentStatus} enum) so that a missing or non-{@code ACTIVE}/{@code INACTIVE}
 * value is rejected by the service at the mandatory-fields step with a clean HTTP 400 and the
 * message code {@code error.project.member.assignment.status.invalid} (Requirement 27 criterion 5,
 * ranked at step 3 of the canonical order of Requirement 3 criterion 7), rather than failing JSON
 * deserialization and surfacing as a generic error.
 *
 * @param userId           the member user's id (mandatory)
 * @param projectId        the owning project id (mandatory; carried in the body, not the path)
 * @param assignmentStatus the target Assignment_Status for a deactivate / reactivate, as a raw
 *                         {@code "ACTIVE"} / {@code "INACTIVE"} string (Requirement 27)
 * @param workerTypeId     the target worker type for a worker-type change (Requirement 14; task 9.1)
 * @param tags             the replacement tag list for a tag change (Requirement 15; task 9.5)
 */
public record UpdateProjectMemberRequest(
        @NotNull Long userId,
        @NotNull Long projectId,
        String assignmentStatus,
        Long workerTypeId,
        List<String> tags
) {}
