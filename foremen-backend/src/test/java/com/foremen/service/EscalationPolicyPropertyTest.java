package com.foremen.service;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.config.offer.OfferEscalationProperties;
import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.OfferProjectSettingsEntity;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property 11 (FOR-05-07-offer-approval): Escalation gates proposals above the threshold.
 *
 * <p><em>For all</em> global caps, optional per-project overrides, discount kinds and proposed
 * values, {@link EscalationPolicy#requiresAdminApproval(DiscountKind, BigDecimal, BigDecimal,
 * OfferProjectSettingsEntity)} returns {@code true} <strong>iff</strong> the proposed {@code value}
 * strictly EXCEEDS the effective cap for its kind, where the effective cap is the per-project
 * override when present and otherwise the GLOBAL default (resolved independently per kind). A value
 * equal to or below the cap does NOT require approval; a value strictly above DOES; and a
 * {@code null} effective cap never requires approval.
 *
 * <p>The {@link EscalationPolicy} is a pure component, so it is constructed directly with an
 * {@link OfferEscalationProperties} instance — no Spring context is needed. Per-project overrides are
 * modeled via an {@link OfferProjectSettingsEntity} whose cap fields are set through its setters.
 *
 * <p>Feature: FOR-05-07-offer-approval, Property 11
 *
 * <p><b>Validates: Requirements 6.2, 6.3, 6.5, 12.3</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 11")
class EscalationPolicyPropertyTest {

    // --- Property: gate == (value > effectiveCap), per-project override precedes global, null => never ---

    @Property(tries = 200)
    void gatesExactlyWhenValueExceedsEffectiveCap(@ForAll("scenarios") Scenario scenario) {
        EscalationPolicy policy = new EscalationPolicy(
                new OfferEscalationProperties(scenario.globalPercentCap(), scenario.globalAbsoluteCap()));

        OfferProjectSettingsEntity projectSettings = scenario.toProjectSettings();

        // The effective cap the policy MUST resolve to for the scenario's kind: per-project override
        // (when present) else the GLOBAL default.
        BigDecimal effectiveCap = scenario.expectedEffectiveCap();
        boolean expected = effectiveCap != null && scenario.value().compareTo(effectiveCap) > 0;

        boolean actual = policy.requiresAdminApproval(
                scenario.kind(), scenario.value(), scenario.scopeBase(), projectSettings);

        assertThat(actual).isEqualTo(expected);

        // Reinforce the boundary semantics explicitly.
        if (effectiveCap == null) {
            // A null effective cap never requires approval, regardless of the proposed value.
            assertThat(actual).isFalse();
        } else if (scenario.value().compareTo(effectiveCap) <= 0) {
            // Equal-to or below the cap: allowed without escalation (R6.2 "does not exceed").
            assertThat(actual).isFalse();
        } else {
            // Strictly above the cap: escalation required (R6.3).
            assertThat(actual).isTrue();
        }
    }

    // --- Property: the effective-cap resolver honors per-project override precedence per kind ---

    @Property(tries = 200)
    void perProjectOverrideTakesPrecedenceOverGlobalDefault(@ForAll("scenarios") Scenario scenario) {
        EscalationPolicy policy = new EscalationPolicy(
                new OfferEscalationProperties(scenario.globalPercentCap(), scenario.globalAbsoluteCap()));
        OfferProjectSettingsEntity projectSettings = scenario.toProjectSettings();

        BigDecimal resolvedPercent = policy.effectivePercentCap(projectSettings);
        BigDecimal resolvedAbsolute = policy.effectiveAbsoluteCap(projectSettings);

        // The effective cap equals the expected cap the scenario derives for each kind.
        BigDecimal expectedPercent = expectedEffectiveCapFor(scenario, DiscountKind.PERCENT);
        BigDecimal expectedAbsolute = expectedEffectiveCapFor(scenario, DiscountKind.ABSOLUTE);

        assertThat(resolvedPercent).isEqualTo(expectedPercent);
        assertThat(resolvedAbsolute).isEqualTo(expectedAbsolute);
    }

    /**
     * The effective cap the policy MUST resolve for a given kind: the per-project override (when a
     * settings row exists and its cap for that kind is non-null) else the GLOBAL default.
     */
    private static BigDecimal expectedEffectiveCapFor(Scenario scenario, DiscountKind kind) {
        BigDecimal override = null;
        if (scenario.hasProjectSettings()) {
            override = kind == DiscountKind.PERCENT
                    ? scenario.overridePercentCap()
                    : scenario.overrideAbsoluteCap();
        }
        BigDecimal global = kind == DiscountKind.PERCENT
                ? scenario.globalPercentCap()
                : scenario.globalAbsoluteCap();
        return override != null ? override : global;
    }

    // --- Providers ---

    /**
     * A generated escalation scenario: a discount kind, a proposed value and scope base, GLOBAL caps
     * (each optionally null), optional per-project override caps (each optionally null), and whether a
     * per-project settings row exists at all. Values are drawn to straddle the caps (below, equal to,
     * and above) so the boundary is exercised densely.
     */
    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<DiscountKind> kinds = Arbitraries.of(DiscountKind.values());
        // Non-negative money/percent-ish amounts with 2-decimal scale; small range keeps values
        // dense around the caps so the > / == / < boundary is hit often.
        Arbitrary<BigDecimal> amounts = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100"))
                .ofScale(2);
        // Optional caps: null models "no cap / no override for that kind".
        Arbitrary<BigDecimal> optionalCaps = Arbitraries.oneOf(
                Arbitraries.just((BigDecimal) null),
                amounts);

        Arbitrary<Boolean> hasProjectSettings = Arbitraries.of(true, false);

        return Combinators.combine(
                        kinds,
                        amounts,        // value
                        amounts,        // scopeBase
                        optionalCaps,   // globalPercentCap
                        optionalCaps,   // globalAbsoluteCap
                        optionalCaps,   // overridePercentCap
                        optionalCaps,   // overrideAbsoluteCap
                        hasProjectSettings)
                .as(Scenario::new);
    }

    /** Immutable generated scenario plus the derived-expectation helpers. */
    record Scenario(
            DiscountKind kind,
            BigDecimal value,
            BigDecimal scopeBase,
            BigDecimal globalPercentCap,
            BigDecimal globalAbsoluteCap,
            BigDecimal overridePercentCap,
            BigDecimal overrideAbsoluteCap,
            boolean hasProjectSettings) {

        /**
         * Builds the per-project override row when {@link #hasProjectSettings} is true, setting its
         * cap fields via setters; returns {@code null} when the project has no settings row (so the
         * policy must fall back to the GLOBAL defaults).
         */
        OfferProjectSettingsEntity toProjectSettings() {
            if (!hasProjectSettings) {
                return null;
            }
            OfferProjectSettingsEntity settings = new OfferProjectSettingsEntity();
            settings.setEscalationPercentCap(overridePercentCap);
            settings.setEscalationAbsoluteCap(overrideAbsoluteCap);
            return settings;
        }

        /**
         * The effective cap for this scenario's {@link #kind}: the per-project override (when a
         * settings row exists and its cap for that kind is non-null) else the GLOBAL default.
         */
        BigDecimal expectedEffectiveCap() {
            return expectedEffectiveCapFor(this, kind);
        }
    }
}
