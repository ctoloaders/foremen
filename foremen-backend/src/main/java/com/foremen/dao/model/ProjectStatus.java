package com.foremen.dao.model;

import java.util.Set;

/**
 * Lifecycle status of a {@code Project}.
 *
 * <p>The offer-preparation slice (FOR-05-07) inserts the {@link #READY_TO_OFFER}, {@link #OFFERED},
 * and {@link #APPROVED} stages between {@link #DRAFT} and {@link #ACTIVE}. FOR-05-07 drives only the
 * {@code READY_TO_OFFER → OFFERED} (offer sent) and {@code OFFERED → APPROVED} (client approves)
 * transitions; the {@code APPROVED → ACTIVE} transition is owned by FOR-05-09 (contract).
 */
public enum ProjectStatus {
    DRAFT,
    READY_TO_OFFER,
    OFFERED,
    APPROVED,
    ACTIVE,
    ON_HOLD,
    COMPLETED,
    CANCELLED;

    /**
     * The pre-activation stages from which a project may be handed over to work
     * ({@code pre-activation → ACTIVE}): {@link #DRAFT}, {@link #READY_TO_OFFER}, {@link #OFFERED},
     * {@link #APPROVED}. Shared single source of truth for both the automatic contract-signed
     * activation ({@code ProjectActivationSignal}, FOR-05-08) and the explicit, readiness-gated
     * activation ({@code ProjectActivationService}, FOR-05-12), so the two paths agree on exactly
     * which statuses are eligible. {@link #ACTIVE} and every later/terminal status are excluded.
     */
    public static final Set<ProjectStatus> PRE_ACTIVATION_STATUSES =
            Set.of(DRAFT, READY_TO_OFFER, OFFERED, APPROVED);

    /**
     * Whether a project in this status is in a pre-activation stage and therefore still eligible to
     * be activated ({@code pre-activation → ACTIVE}); see {@link #PRE_ACTIVATION_STATUSES}.
     *
     * @return {@code true} iff this status is one of {@link #PRE_ACTIVATION_STATUSES}
     */
    public boolean isPreActivation() {
        return PRE_ACTIVATION_STATUSES.contains(this);
    }
}
