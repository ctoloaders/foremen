package com.foremen.controller.model;

import com.foremen.service.team.ReadinessState;

/**
 * The team readiness gate returned by {@code GET /api/project-members/readiness} (FOR-05-09
 * Requirement 20, parent {@code design.md} §6.3).
 *
 * <p>{@link #key()} is the constant gate key {@code "team"}; {@link #state()} is {@link
 * ReadinessState#DONE} iff the project has at least one ACTIVE {@code FOREMAN} member and {@link
 * ReadinessState#BLOCKED} otherwise; {@link #counts()} reports, per Assignable_Project_Role, the
 * number of <strong>ACTIVE</strong> members of that role (INACTIVE members excluded, D14).
 *
 * @param key    the gate key — always {@value #KEY}
 * @param state  the gate state (DONE / BLOCKED)
 * @param counts the per-role ACTIVE member counts
 */
public record TeamReadiness(
        String key,
        ReadinessState state,
        Counts counts
) {

    /** The constant readiness gate key this spec contributes. */
    public static final String KEY = "team";

    /**
     * The per-role counts of <strong>ACTIVE</strong> Project_Members, one field per
     * Assignable_Project_Role ({@code MANAGER}, {@code FOREMAN}, {@code ESTIMATOR}, {@code WORKER},
     * {@code FINANCIER}, {@code CLIENT}); {@code 0} when a role has no ACTIVE member.
     *
     * @param manager   ACTIVE {@code MANAGER} members
     * @param foreman   ACTIVE {@code FOREMAN} members (drives the {@code team} gate state)
     * @param estimator ACTIVE {@code ESTIMATOR} members
     * @param worker    ACTIVE {@code WORKER} members
     * @param financier ACTIVE {@code FINANCIER} members
     * @param client    ACTIVE {@code CLIENT} members
     */
    public record Counts(
            int manager,
            int foreman,
            int estimator,
            int worker,
            int financier,
            int client
    ) {}
}
