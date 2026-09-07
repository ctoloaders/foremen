package com.foremen.controller.model;

/**
 * A single project-team member as exposed in the project list/read DTOs (FOR-04-13 Requirement 3.4).
 * Materialized from the {@code ProjectEntity.members} read-only collection at projection time.
 *
 * @param userId   the member user's id
 * @param userName the member user's display name
 * @param roleCode the project-role code (e.g. {@code "CLIENT"}, {@code "FOREMAN"})
 * @param roleName the localized project-role name
 */
public record ProjectMemberSummaryDto(
        Long userId,
        String userName,
        String roleCode,
        String roleName
) {}
