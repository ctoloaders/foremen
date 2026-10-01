package com.foremen.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.foremen.config.offer.OfferEscalationProperties;
import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.OfferProjectSettingsEntity;

/**
 * Pure collaborator resolving the effective offer {@code Escalation_Threshold} and gating a
 * {@code MANAGER_PROPOSAL} on it (FOR-05-07, Requirements 6.2, 6.3, 6.4).
 *
 * <p>The threshold is a percent and/or absolute cap on the value a MANAGER may propose on a
 * {@code MANAGER_PROPOSAL} without ADMIN approval. Its default is the GLOBAL application config
 * ({@link OfferEscalationProperties}, {@code foremen.offer.escalation.percent-cap} /
 * {@code ...absolute-cap}); a project MAY override either cap through
 * {@link OfferProjectSettingsEntity}. Resolution is per-cap and independent (Requirement 6.4):
 *
 * <ul>
 *   <li>the effective percent cap = the project's {@code escalationPercentCap} when non-null, else
 *       the GLOBAL {@code percentCap};</li>
 *   <li>the effective absolute cap = the project's {@code escalationAbsoluteCap} when non-null, else
 *       the GLOBAL {@code absoluteCap}.</li>
 * </ul>
 *
 * <p>A {@code null} effective cap for a given kind means "no cap" — a proposal of that kind never
 * requires ADMIN approval. {@link #requiresAdminApproval(DiscountKind, BigDecimal, BigDecimal)}
 * returns {@code true} exactly when the proposed value <strong>exceeds</strong> the effective cap for
 * its kind (Requirement 6.3): a value equal to the cap is allowed without escalation (Requirement
 * 6.2 — "does not exceed").
 *
 * <p>Cap semantics by kind:
 * <ul>
 *   <li>{@link DiscountKind#PERCENT}: the proposed percentage value is compared directly against the
 *       percent cap (both are percentages).</li>
 *   <li>{@link DiscountKind#ABSOLUTE}: the proposed amount is compared directly against the absolute
 *       cap (both are money amounts in the offer currency). The {@code scopeBase} is accepted for
 *       parity with the percent path and to support future base-relative gating, but the absolute
 *       cap is an absolute money threshold and is applied as-is.</li>
 * </ul>
 *
 * <p>This component is <strong>pure</strong>: it performs no persistence and holds no state beyond
 * the injected GLOBAL defaults; its decision depends only on its arguments and the resolved caps.
 */
@Component
public class EscalationPolicy {

    private final OfferEscalationProperties globalDefaults;

    public EscalationPolicy(OfferEscalationProperties globalDefaults) {
        this.globalDefaults = globalDefaults;
    }

    /**
     * Resolves the effective percent cap for a project: the per-project override when present, else
     * the GLOBAL config default. A {@code null} result means "no percent cap".
     *
     * @param projectSettings the per-project override row, or {@code null} when the project has none
     * @return the effective percent cap, or {@code null} when neither an override nor a GLOBAL default
     *         is configured
     */
    public BigDecimal effectivePercentCap(OfferProjectSettingsEntity projectSettings) {
        BigDecimal override = projectSettings == null ? null : projectSettings.getEscalationPercentCap();
        return override != null ? override : globalDefaults.percentCap();
    }

    /**
     * Resolves the effective absolute cap for a project: the per-project override when present, else
     * the GLOBAL config default. A {@code null} result means "no absolute cap".
     *
     * @param projectSettings the per-project override row, or {@code null} when the project has none
     * @return the effective absolute cap, or {@code null} when neither an override nor a GLOBAL default
     *         is configured
     */
    public BigDecimal effectiveAbsoluteCap(OfferProjectSettingsEntity projectSettings) {
        BigDecimal override = projectSettings == null ? null : projectSettings.getEscalationAbsoluteCap();
        return override != null ? override : globalDefaults.absoluteCap();
    }

    /**
     * Returns {@code true} when the proposed {@code MANAGER_PROPOSAL} value exceeds the effective cap
     * for its kind and therefore requires ADMIN approval (Requirement 6.3); {@code false} when it is
     * within (less than or equal to) the cap (Requirement 6.2) or when no cap is configured for that
     * kind. This overload resolves against the GLOBAL defaults only (no per-project override).
     *
     * @param kind      the proposed discount kind ({@code PERCENT} / {@code ABSOLUTE})
     * @param value     the manager's proposed value (percentage for {@code PERCENT}, money amount for
     *                  {@code ABSOLUTE})
     * @param scopeBase the net base of the proposal's scope (line/category/offer subtotal); used for
     *                  base-relative gating and validation parity
     * @return {@code true} iff the proposal must be escalated to ADMIN approval
     */
    public boolean requiresAdminApproval(DiscountKind kind, BigDecimal value, BigDecimal scopeBase) {
        return requiresAdminApproval(kind, value, scopeBase, null);
    }

    /**
     * Returns {@code true} when the proposed {@code MANAGER_PROPOSAL} value exceeds the effective cap
     * for its kind — resolved as the per-project override when present, else the GLOBAL default — and
     * therefore requires ADMIN approval (Requirements 6.3, 6.4). Returns {@code false} when the value
     * is within (less than or equal to) the cap (Requirement 6.2) or when no cap is configured for
     * that kind.
     *
     * @param kind            the proposed discount kind ({@code PERCENT} / {@code ABSOLUTE})
     * @param value           the manager's proposed value
     * @param scopeBase       the net base of the proposal's scope; used for base-relative gating and
     *                        validation parity
     * @param projectSettings the per-project escalation override, or {@code null} to use the GLOBAL
     *                        defaults only
     * @return {@code true} iff the proposal must be escalated to ADMIN approval
     */
    public boolean requiresAdminApproval(DiscountKind kind,
                                         BigDecimal value,
                                         BigDecimal scopeBase,
                                         OfferProjectSettingsEntity projectSettings) {
        if (kind == null || value == null) {
            return false;
        }
        BigDecimal cap = switch (kind) {
            case PERCENT -> effectivePercentCap(projectSettings);
            case ABSOLUTE -> effectiveAbsoluteCap(projectSettings);
        };
        // No cap configured for this kind => never escalate.
        if (cap == null) {
            return false;
        }
        // Exceeds the cap => escalate; equal-to-cap is allowed (Requirement 6.2 "does not exceed").
        return value.compareTo(cap) > 0;
    }
}
