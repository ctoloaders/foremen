package com.foremen.controller.model;

import com.foremen.service.team.ReadinessState;

/**
 * The team readiness gate returned by {@code GET /api/project-members/readiness} (FOR-05-09
 * Requirement 20, parent {@code design.md} §6.3).
 *
 * <p>{@link #key()} is the constant gate key {@code "team"}; {@link #counts()} reports, per
 * Assignable_Project_Role, the number of <strong>ACTIVE</strong> members of that role (INACTIVE
 * members excluded, D14).
 *
 * <p><b>State (enhancement batch, user decision 5b).</b> The gate requires an ACTIVE MANAGER:
 * {@link ReadinessState#BLOCKED} with {@link #messageKey()}
 * {@code team.readiness.managerRequiredBlocker} when there is no ACTIVE MANAGER;
 * {@link ReadinessState#WARNING} with {@code team.readiness.managerActsAsForemanWarning} when an
 * ACTIVE MANAGER exists but no ACTIVE FOREMAN (the manager acts as foreman — ready, non-blocking);
 * {@link ReadinessState#DONE} with a {@code null} {@link #messageKey()} when both an ACTIVE MANAGER
 * and an ACTIVE FOREMAN exist.
 *
 * @param key        the gate key — always {@value #KEY}
 * @param state      the gate state (DONE / WARNING / BLOCKED)
 * @param counts     the per-role ACTIVE member counts
 * @param messageKey a localized i18n key describing the blocker / warning, or {@code null} when DONE
 */
public record TeamReadiness(
        String key,
        ReadinessState state,
        Counts counts,
        String messageKey
) {

    /** The constant readiness gate key this spec contributes. */
    public static final String KEY = "team";

    /** Blocker message key when there is no ACTIVE MANAGER. */
    public static final String MANAGER_REQUIRED_BLOCKER = "team.readiness.managerRequiredBlocker";

    /** Warning message key when an ACTIVE MANAGER exists but no ACTIVE FOREMAN. */
    public static final String MANAGER_ACTS_AS_FOREMAN_WARNING =
            "team.readiness.managerActsAsForemanWarning";

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
