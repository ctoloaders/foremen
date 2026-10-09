package com.foremen.service.team;

/**
 * The three disjoint presentation blocks a {@code Project_Member} is placed in, derived purely from
 * the member's {@code Company_Role} (FOR-05-09 D3/D6, Requirement 6.2).
 *
 * <p>The declaration order ({@link #ADMIN_STAFF}, {@link #WORKERS}, {@link #CLIENTS}) is the
 * canonical block order used when listing a team (Requirement 4.6); {@link Enum#ordinal()} is the
 * primary sort key of {@link TeamMemberOrdering}.
 */
public enum TeamBlock {

    /** Every admin-staff role: {@code MANAGER}, {@code FOREMAN}, {@code ESTIMATOR}, {@code FINANCIER}, and any other non-worker, non-client role. */
    ADMIN_STAFF,

    /** The {@code WORKER} role. */
    WORKERS,

    /** The {@code CLIENT} role. */
    CLIENTS
}
