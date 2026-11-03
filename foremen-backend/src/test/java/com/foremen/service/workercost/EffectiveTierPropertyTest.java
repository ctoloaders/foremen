package com.foremen.service.workercost;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.estimate.margins.MarginCostService;
import com.foremen.service.estimate.margins.WorkerTypeTier;
import com.foremen.service.workercost.WorkerCostDerivation.ResolvedTier;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-07-06 (design §Correctness properties 1–2; task 2.1) — property tests for {@link EffectiveTier}.
 *
 * <p><b>Validates: Requirements 2.2, 2.3, 2.4</b>
 */
class EffectiveTierPropertyTest {

    private static final long BASE_ID = 1L;
    private static final long TYPE_ID = 2L;

    // =============================================================================================
    // Property 1 — unitCost = baseCost × (1 + u + s); s = 0 equals the FOR-06-10 value.
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-06-company-payroll, Property 1: Effective tier")
    void unitCostIsBaseCostTimesOnePlusUpliftPlusSeniority(
            @ForAll("baseShares") BigDecimal baseShare,
            @ForAll("rates") BigDecimal rate,
            @ForAll("uplifts") BigDecimal uplift,
            @ForAll("seniorities") BigDecimal seniority,
            @ForAll boolean workerIsBaseType) {
        WorkerTypeEntity baseType = type(BASE_ID, true, baseShare, true);
        WorkerTypeEntity workerType = workerIsBaseType ? baseType : type(TYPE_ID, false, uplift, true);
        BigDecimal u = workerIsBaseType ? BigDecimal.ZERO : uplift;
        WorkerTypeTier baseTier = WorkerTypeTier.base(baseShare);

        ResolvedTier resolved = EffectiveTier.of(workerType, seniority, baseType);
        BigDecimal unit = WorkerCostDerivation.unitServiceCost(rate, resolved.tier(), baseTier);

        BigDecimal expected = MarginCostService.baseCost(rate, baseShare)
                .multiply(BigDecimal.ONE.add(u).add(seniority));
        assertThat(unit).as("unitCost = baseCost × (1 + u + s) (R2.3)").isEqualByComparingTo(expected);
        assertThat(resolved.baseTierFallback()).isFalse();
        assertThat(resolved.workerTypeId()).isEqualTo(workerType.getId());
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-07-06-company-payroll, Property 1: Effective tier")
    void zeroSeniorityEqualsTheFor0610Value(
            @ForAll("baseShares") BigDecimal baseShare,
            @ForAll("rates") BigDecimal rate,
            @ForAll("uplifts") BigDecimal uplift,
            @ForAll boolean workerIsBaseType,
            @ForAll boolean nullSeniority) {
        WorkerTypeEntity baseType = type(BASE_ID, true, baseShare, true);
        WorkerTypeEntity workerType = workerIsBaseType ? baseType : type(TYPE_ID, false, uplift, true);
        WorkerTypeTier baseTier = WorkerTypeTier.base(baseShare);

        ResolvedTier now = EffectiveTier.of(workerType, nullSeniority ? null : BigDecimal.ZERO, baseType);
        ResolvedTier legacy = WorkerCostDerivation.tierFor(7L, Map.of(7L, workerType), baseType);

        assertThat(now).as("s = 0 ⇒ identical resolution to FOR-06-10 (R2.4)").isEqualTo(legacy);
        assertThat(WorkerCostDerivation.unitServiceCost(rate, now.tier(), baseTier))
                .isEqualByComparingTo(WorkerCostDerivation.unitServiceCost(rate, legacy.tier(), baseTier));
    }

    // =============================================================================================
    // Property 2 — no/inactive type costs as base (+ s) and is flagged; flag set exactly then.
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-06-company-payroll, Property 2: Fallback")
    void fallbackFlagSetExactlyWhenTypeMissingOrInactive(
            @ForAll("baseShares") BigDecimal baseShare,
            @ForAll("rates") BigDecimal rate,
            @ForAll("uplifts") BigDecimal uplift,
            @ForAll("seniorities") BigDecimal seniority,
            @ForAll("typeStates") TypeState state) {
        WorkerTypeEntity baseType = type(BASE_ID, true, baseShare, true);
        WorkerTypeEntity workerType = switch (state) {
            case MISSING -> null;
            case INACTIVE_NON_BASE -> type(TYPE_ID, false, uplift, false);
            case ACTIVE_NON_BASE -> type(TYPE_ID, false, uplift, true);
            case ACTIVE_BASE -> baseType;
        };
        boolean expectFallback = state == TypeState.MISSING || state == TypeState.INACTIVE_NON_BASE;
        WorkerTypeTier baseTier = WorkerTypeTier.base(baseShare);

        ResolvedTier resolved = EffectiveTier.of(workerType, seniority, baseType);

        assertThat(resolved.baseTierFallback()).as("fallback ⇔ missing/inactive (R2.2)").isEqualTo(expectFallback);
        if (expectFallback) {
            assertThat(resolved.workerTypeId()).isNull();
            BigDecimal expected = MarginCostService.baseCost(rate, baseShare)
                    .multiply(BigDecimal.ONE.add(seniority));
            assertThat(WorkerCostDerivation.unitServiceCost(rate, resolved.tier(), baseTier))
                    .as("fallback costs as base (+ s)").isEqualByComparingTo(expected);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Generators / helpers
    // ---------------------------------------------------------------------------------------------

    enum TypeState { MISSING, INACTIVE_NON_BASE, ACTIVE_NON_BASE, ACTIVE_BASE }

    @Provide
    Arbitrary<TypeState> typeStates() {
        return Arbitraries.of(TypeState.class);
    }

    @Provide
    Arbitrary<BigDecimal> baseShares() {
        return Arbitraries.bigDecimals().between(new BigDecimal("0.10"), new BigDecimal("0.90")).ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> rates() {
        return Arbitraries.bigDecimals().between(new BigDecimal("1.00"), new BigDecimal("10000.00")).ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> uplifts() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("2.0000")).ofScale(4);
    }

    @Provide
    Arbitrary<BigDecimal> seniorities() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, BigDecimal.ONE).ofScale(4);
    }

    private static WorkerTypeEntity type(long id, boolean base, BigDecimal tierPct, boolean active) {
        WorkerTypeEntity t = new WorkerTypeEntity();
        t.setId(id);
        t.setBase(base);
        t.setTierPct(tierPct);
        t.setActive(active);
        return t;
    }
}
