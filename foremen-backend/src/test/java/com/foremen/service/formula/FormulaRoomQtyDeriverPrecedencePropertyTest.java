package com.foremen.service.formula;

import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.dao.model.formula.FormulaAst;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for
 * {@link FormulaRoomQtyDeriver#resolveApplicableFormula(WorkPackageOverrideEntity, WorkVolumeFormulaEntity)}
 * (FOR-05-04, Property 7 — "Package override precedence").
 *
 * <p>The precedence rule under test (per the method's own javadoc and design.md §6.5
 * {@code applicableFormula}):
 * <ol>
 *   <li>an override that carries a non-null {@code overrideParsedAst} always wins, regardless of
 *       whether a default formula also exists (Requirement 4.2);</li>
 *   <li>an override that exists but carries no {@code overrideParsedAst} (flag-only membership)
 *       does NOT suppress the default formula — the default still applies if present
 *       (Requirement 5.3);</li>
 *   <li>with neither an override formula nor a default formula, the result is {@code null} —
 *       hand entry is preserved (Requirement 5.4).</li>
 * </ol>
 *
 * <p>The five discrete combinations of (override present/absent × override carries an AST or
 * not × default present/absent) form an inherently combinatorial decision table, so the boundary
 * cases are pinned down with {@code @Example}s; two {@code @Property} methods then randomize the
 * AST *content* (distinct {@code Const} literals) while holding a fixed presence/absence
 * combination, to confirm the winning branch always returns the exact AST reference associated
 * with that branch rather than merely "some AST".
 *
 * <p>Feature: FOR-05-04-estimate-packages-changes, Property 7
 *
 * <p><b>Validates: Requirements 4.2, 5.3</b>
 */
@Tag("Feature: FOR-05-04-estimate-packages-changes, Property 7")
class FormulaRoomQtyDeriverPrecedencePropertyTest {

    // ------------------------------------------------------------------------------------------
    // Boundary cases (discrete decision table) — @Example
    // ------------------------------------------------------------------------------------------

    /**
     * Override present with a parsed AST, default also present -> override wins.
     * Validates: Requirement 4.2
     */
    @Example
    void overrideWithAstWinsOverDefault() {
        FormulaAst overrideAst = new FormulaAst.Const(BigDecimal.ONE);
        FormulaAst defaultAst = new FormulaAst.Const(BigDecimal.TEN);

        WorkPackageOverrideEntity override = override(true, overrideAst);
        WorkVolumeFormulaEntity defaultFormula = defaultFormula(defaultAst);

        FormulaAst result = FormulaRoomQtyDeriver.resolveApplicableFormula(override, defaultFormula);

        assertThat(result).isSameAs(overrideAst);
    }

    /**
     * Override present with a parsed AST, no default -> override still wins.
     * Validates: Requirement 4.2
     */
    @Example
    void overrideWithAstWinsWithNoDefault() {
        FormulaAst overrideAst = new FormulaAst.Const(BigDecimal.valueOf(2));

        WorkPackageOverrideEntity override = override(true, overrideAst);

        FormulaAst result = FormulaRoomQtyDeriver.resolveApplicableFormula(override, null);

        assertThat(result).isSameAs(overrideAst);
    }

    /**
     * Override present but flag-only (no parsed AST), default present -> default applies (the
     * flag-only override does not suppress it).
     * Validates: Requirement 5.3
     */
    @Example
    void flagOnlyOverrideDoesNotSuppressDefault() {
        FormulaAst defaultAst = new FormulaAst.Const(BigDecimal.valueOf(3));

        WorkPackageOverrideEntity override = override(true, null);
        WorkVolumeFormulaEntity defaultFormula = defaultFormula(defaultAst);

        FormulaAst result = FormulaRoomQtyDeriver.resolveApplicableFormula(override, defaultFormula);

        assertThat(result).isSameAs(defaultAst);
    }

    /**
     * Override present but flag-only (no parsed AST), no default -> no applicable formula.
     * Validates: Requirement 5.3
     */
    @Example
    void flagOnlyOverrideWithNoDefaultResolvesToNull() {
        WorkPackageOverrideEntity override = override(true, null);

        FormulaAst result = FormulaRoomQtyDeriver.resolveApplicableFormula(override, null);

        assertThat(result).isNull();
    }

    /**
     * No override at all, default present -> default applies.
     * Validates: Requirement 5.3
     */
    @Example
    void noOverrideFallsBackToDefault() {
        FormulaAst defaultAst = new FormulaAst.Const(BigDecimal.valueOf(4));

        WorkVolumeFormulaEntity defaultFormula = defaultFormula(defaultAst);

        FormulaAst result = FormulaRoomQtyDeriver.resolveApplicableFormula(null, defaultFormula);

        assertThat(result).isSameAs(defaultAst);
    }

    /**
     * Neither an override nor a default -> null, hand entry preserved.
     * Validates: Requirement 5.4 (documented boundary of the precedence rule)
     */
    @Example
    void neitherOverrideNorDefaultResolvesToNull() {
        FormulaAst result = FormulaRoomQtyDeriver.resolveApplicableFormula(null, null);

        assertThat(result).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Property: override with a non-null AST always wins, for any randomized AST content and
    // regardless of whether a default (with any other randomized AST content) also exists.
    // Validates: Requirement 4.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 7")
    void overrideAlwaysWinsWhenItCarriesAFormula(
            @ForAll("distinctConstPair") ConstPair pair,
            @ForAll boolean overrideMember,
            @ForAll boolean defaultPresent) {
        WorkPackageOverrideEntity override = override(overrideMember, pair.overrideAst());
        WorkVolumeFormulaEntity defaultFormula = defaultPresent ? defaultFormula(pair.defaultAst()) : null;

        FormulaAst result = FormulaRoomQtyDeriver.resolveApplicableFormula(override, defaultFormula);

        assertThat(result).isSameAs(pair.overrideAst());
    }

    // ------------------------------------------------------------------------------------------
    // Property: when the override carries no formula, the default (if any, with randomized
    // content) applies; with no default, the result is null. This holds regardless of the
    // override's own presence/membership flag as long as it has no parsed AST.
    // Validates: Requirement 5.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 7")
    void flagOnlyOrAbsentOverrideDefersToDefault(
            @ForAll("anyConst") FormulaAst defaultAst,
            @ForAll boolean overridePresent,
            @ForAll boolean overrideMember,
            @ForAll boolean defaultPresent) {
        WorkPackageOverrideEntity override = overridePresent ? override(overrideMember, null) : null;
        WorkVolumeFormulaEntity defaultFormula = defaultPresent ? defaultFormula(defaultAst) : null;

        FormulaAst result = FormulaRoomQtyDeriver.resolveApplicableFormula(override, defaultFormula);

        if (defaultPresent) {
            assertThat(result).isSameAs(defaultAst);
        } else {
            assertThat(result).isNull();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private static WorkPackageOverrideEntity override(boolean member, FormulaAst overrideParsedAst) {
        WorkPackageOverrideEntity override = new WorkPackageOverrideEntity();
        override.setMember(member);
        override.setOverrideParsedAst(overrideParsedAst);
        override.setOverrideSourceText(overrideParsedAst != null ? "irrelevant-source-text" : null);
        return override;
    }

    private static WorkVolumeFormulaEntity defaultFormula(FormulaAst parsedAst) {
        WorkVolumeFormulaEntity formula = new WorkVolumeFormulaEntity();
        formula.setSourceText("irrelevant-source-text");
        formula.setParsedAst(parsedAst);
        return formula;
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<FormulaAst> anyConst() {
        return Arbitraries.longs().between(0, 9_999_999)
                .map(v -> new FormulaAst.Const(BigDecimal.valueOf(v, 2)));
    }

    /**
     * A pair of distinct {@code Const} ASTs (override's vs. default's), so a test asserting
     * "the override AST won" cannot accidentally pass because the two literals happened to be
     * structurally equal.
     */
    @Provide
    Arbitrary<ConstPair> distinctConstPair() {
        return Arbitraries.longs().between(0, 9_999_999)
                .list().ofSize(2).uniqueElements()
                .map(values -> new ConstPair(
                        new FormulaAst.Const(BigDecimal.valueOf(values.get(0), 2)),
                        new FormulaAst.Const(BigDecimal.valueOf(values.get(1), 2))));
    }

    private record ConstPair(FormulaAst overrideAst, FormulaAst defaultAst) {
    }
}
