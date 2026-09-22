package com.foremen.service.formula;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.dao.model.formula.FormulaAst.BinOp;
import com.foremen.dao.model.formula.FormulaAst.BinOperator;
import com.foremen.dao.model.formula.FormulaAst.Const;
import com.foremen.dao.model.formula.FormulaAst.Var;
import com.foremen.dao.model.formula.FormulaAst.WorkRef;
import com.foremen.dao.model.formula.FormulaAst.WorkRefMode;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link FormulaEvaluator#evaluate(FormulaAst, Map, java.util.function.Function)}
 * (FOR-05-04, Property 6 — "Absent reference resolves to zero").
 *
 * <p>A formula may reference a work that is not present in the room (i.e. the room has no
 * resolved volume for that {@link WorkRef}). Per §6.4 of the design and the evaluator's own
 * contract, such a reference does not raise an error: it contributes {@code 0} to the
 * arithmetic (Requirement 3.4). The caller signals "absent" by having {@code resolvedVolumeOf}
 * return {@code null} for that ref (mirroring {@code evaluateRoom}'s
 * {@code resolved.getOrDefault(ref, 0)} default at the planner layer) — this test exercises the
 * evaluator's own null-tolerant handling directly, plus the equivalent case of an explicit
 * {@link BigDecimal#ZERO} resolution.
 *
 * <p>The complementary "null dimension -> 0" contract for {@link Var} nodes (a room dimension
 * missing from the {@code vars} map) is the analogous "absent" case for room-dimension
 * variables and is asserted alongside the {@link WorkRef} properties.
 *
 * <p>No Spring context and no database — {@link FormulaEvaluator} is a pure static utility.
 *
 * <p>Feature: FOR-05-04-estimate-packages-changes, Property 6
 *
 * <p><b>Validates: Requirements 3.4</b>
 */
@Tag("Feature: FOR-05-04-estimate-packages-changes, Property 6")
class FormulaEvaluatorAbsentReferencePropertyTest {

    // ------------------------------------------------------------------------------------------
    // Property 6a: a VOLUME-mode WorkRef whose resolvedVolumeOf resolves to null (absent work)
    // evaluates to exactly zero.
    // Validates: Requirement 3.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 6")
    void absentVolumeRefResolvesToZero(@ForAll("refNames") String ref) {
        WorkRef workRef = new WorkRef(ref, WorkRefMode.VOLUME);

        BigDecimal result = FormulaEvaluator.evaluate(workRef, Map.of(), r -> null);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------------------------------
    // Property 6b: a PRESENT-mode WorkRef evaluates to zero whenever the resolved volume is
    // null or <= 0 (the "not present" case), and to one whenever it resolves to a strictly
    // positive value.
    // Validates: Requirement 3.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 6")
    void absentOrNonPositivePresentRefResolvesToZeroPositiveResolvesToOne(
            @ForAll("refNames") String ref, @ForAll("nullableNonPositiveOrPositive") BigDecimal resolved) {
        WorkRef workRef = new WorkRef(ref, WorkRefMode.PRESENT);

        BigDecimal result = FormulaEvaluator.evaluate(workRef, Map.of(), r -> resolved);

        boolean isPositive = resolved != null && resolved.compareTo(BigDecimal.ZERO) > 0;
        if (isPositive) {
            assertThat(result).isEqualByComparingTo(BigDecimal.ONE);
        } else {
            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 6c: explicit BigDecimal.ZERO resolution (an alternative "absent" signalling
    // convention, e.g. from evaluateRoom's getOrDefault(ref, 0)) behaves identically to null for
    // both VOLUME and PRESENT modes.
    // Validates: Requirement 3.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 6")
    void explicitZeroResolutionBehavesLikeNullResolution(@ForAll("refNames") String ref) {
        WorkRef volumeRef = new WorkRef(ref, WorkRefMode.VOLUME);
        WorkRef presentRef = new WorkRef(ref, WorkRefMode.PRESENT);

        BigDecimal nullVolume = FormulaEvaluator.evaluate(volumeRef, Map.of(), r -> null);
        BigDecimal zeroVolume = FormulaEvaluator.evaluate(volumeRef, Map.of(), r -> BigDecimal.ZERO);
        BigDecimal nullPresent = FormulaEvaluator.evaluate(presentRef, Map.of(), r -> null);
        BigDecimal zeroPresent = FormulaEvaluator.evaluate(presentRef, Map.of(), r -> BigDecimal.ZERO);

        assertThat(nullVolume).isEqualByComparingTo(zeroVolume);
        assertThat(nullPresent).isEqualByComparingTo(zeroPresent);
        assertThat(nullVolume).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(nullPresent).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------------------------------
    // Property 6d: a Var whose name is missing from the vars map (the analogous "absent" case
    // for room-dimension variables) evaluates to zero, per FormulaEvaluator's "null dimension
    // -> 0" contract.
    // Validates: Requirement 3.4 (complementary assertion)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 6")
    void missingVarResolvesToZero(@ForAll("dimensionNames") String dimensionName) {
        Var variable = new Var(dimensionName);

        // vars deliberately does not contain dimensionName.
        BigDecimal result = FormulaEvaluator.evaluate(variable, Map.of(), r -> null);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------------------------------
    // Property 6e: wrapping an absent WorkRef inside a composite arithmetic expression
    // (BinOp(ADD, WorkRef(absent, VOLUME), Const(k))) confirms the absent branch contributes
    // exactly zero to the result — the composite evaluates to exactly k.
    // Validates: Requirement 3.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 6")
    void absentRefInCompositeExpressionContributesExactlyZero(
            @ForAll("refNames") String ref, @ForAll("constants") BigDecimal k) {
        BinOp addAbsentAndConst = new BinOp(BinOperator.ADD, new WorkRef(ref, WorkRefMode.VOLUME), new Const(k));

        BigDecimal result = FormulaEvaluator.evaluate(addAbsentAndConst, Map.of(), r -> null);

        assertThat(result).isEqualByComparingTo(k);
    }

    // ------------------------------------------------------------------------------------------
    // Property 6f: the absent-ref-contributes-zero property holds even when only SOME refs in
    // a multi-ref expression are absent — mixing an absent ref with a present (resolved) one
    // sums correctly, i.e. the absent operand never perturbs the result beyond adding zero.
    // Validates: Requirement 3.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 6")
    void absentRefMixedWithResolvedRefContributesOnlyTheResolvedValue(
            @ForAll("refNames") String absentRef, @ForAll("refNames") String presentRef,
            @ForAll("constants") BigDecimal presentVolume) {
        Assume.that(!absentRef.equals(presentRef));
        Map<WorkRef, BigDecimal> resolved = new HashMap<>();
        resolved.put(new WorkRef(presentRef, WorkRefMode.VOLUME), presentVolume);
        // absentRef intentionally absent from `resolved`.

        BinOp sumOfBoth = new BinOp(BinOperator.ADD,
                new WorkRef(absentRef, WorkRefMode.VOLUME),
                new WorkRef(presentRef, WorkRefMode.VOLUME));

        BigDecimal result = FormulaEvaluator.evaluate(sumOfBoth, Map.of(), resolved::get);

        assertThat(result).isEqualByComparingTo(presentVolume);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> refNames() {
        return Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(3)
                .flatMap(letters -> Arbitraries.integers().between(1, 999)
                        .map(n -> letters + n));
    }

    @Provide
    Arbitrary<String> dimensionNames() {
        return Arbitraries.of(FormulaAst.ROOM_DIMENSION_VARS.toArray(new String[0]));
    }

    @Provide
    Arbitrary<BigDecimal> constants() {
        return Arbitraries.longs().between(-999_999, 999_999)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    /**
     * Either {@code null} (absent), a non-positive value, or a strictly positive value — the
     * full input space relevant to {@link WorkRefMode#PRESENT}'s {@code > 0} test.
     */
    @Provide
    Arbitrary<BigDecimal> nullableNonPositiveOrPositive() {
        Arbitrary<BigDecimal> nonPositive = Arbitraries.longs().between(-999_999, 0)
                .map(cents -> BigDecimal.valueOf(cents, 2));
        Arbitrary<BigDecimal> positive = Arbitraries.longs().between(1, 999_999)
                .map(cents -> BigDecimal.valueOf(cents, 2));
        Arbitrary<BigDecimal> nullValue = Arbitraries.just(null);

        return Arbitraries.oneOf(nullValue, nonPositive, positive);
    }
}
