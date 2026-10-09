package com.foremen.dao.model;

/**
 * FOR-05-09 (R27, D14) — the lifecycle status of a {@link ProjectMemberEntity}. A soft
 * deactivation keeps the membership (and its history) while excluding it from the readiness
 * FOREMAN count and the last-ACTIVE-MANAGER / last-ACTIVE-CLIENT invariants. Stored on
 * {@code project_members.assignment_status} (NOT NULL, default {@code ACTIVE}).
 */
public enum AssignmentStatus {
    ACTIVE,
    INACTIVE
}
