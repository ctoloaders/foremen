package com.foremen.service.team;

/**
 * The state of a readiness gate (FOR-05-09 Requirement 20, parent {@code design.md} §6.3).
 *
 * <p>For the {@code team} gate this spec contributes, the state is {@link #DONE} iff the project has
 * at least one ACTIVE {@code FOREMAN} member, and {@link #BLOCKED} otherwise. INACTIVE members never
 * count toward readiness (D14).
 */
public enum ReadinessState {

    /** The gate's condition is satisfied. */
    DONE,

    /**
     * The gate's hard condition is satisfied but a non-blocking advisory applies (enhancement batch,
     * user decision 5b): an ACTIVE MANAGER exists but no ACTIVE FOREMAN, so the manager acts as
     * foreman — the project is ready (not blocked) but surfaces a warning.
     */
    WARNING,

    /** The gate's condition is not yet satisfied (a hard blocker). */
    BLOCKED
}
