package com.foremen.service.offer;

import java.math.BigDecimal;

/**
 * The manager/admin control-state block of the {@link ExecutorOfferReadModel} (FOR-05-07,
 * Requirement 16). It carries the offer-workflow control flags a MANAGER/ADMIN needs to drive the
 * negotiation — <b>not</b> estimate internals.
 *
 * <p>Per Requirement 16 the kosztorys cost/margin/worker-rate figures remain their own
 * MANAGER/ADMIN-only ESTIMATE / margins read models ({@code MarginsListDto} etc.); this offer-domain
 * block therefore intentionally exposes only offer-negotiation control state (escalation thresholds,
 * pending-approval and writability flags), keeping the offer read models a single confidentiality
 * boundary rather than folding the estimate margin DTOs into them.
 *
 * @param editable                 whether the offer may currently be written by the caller
 * @param hasPendingAdminApproval  whether a proposal above the escalation threshold awaits ADMIN
 *                                 approval
 * @param escalationPercentCap     the effective escalation percent cap (per-project override else
 *                                 GLOBAL default), or {@code null}
 * @param escalationAbsoluteCap    the effective escalation absolute cap, or {@code null}
 * @param openRounds               the number of open/unresolved negotiation rounds awaiting a
 *                                 manager response
 */
public record ManagerControlState(
        boolean editable,
        boolean hasPendingAdminApproval,
        BigDecimal escalationPercentCap,
        BigDecimal escalationAbsoluteCap,
        int openRounds) {
}
