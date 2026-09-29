package com.foremen.service.formula;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.dao.model.formula.FormulaAst.BinOperator;
import com.foremen.dao.model.formula.FormulaAst.BinOp;
import com.foremen.dao.model.formula.FormulaAst.Const;
import com.foremen.dao.model.formula.FormulaAst.Var;
import com.foremen.service.formula.VolumeResolver.Resolution;
import com.foremen.service.formula.VolumeResolver.Source;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based test for {@link VolumeResolver} (FOR-05-05, task 4.2).
 *
 * <p>Volume resolution follows a strict precedence: a per-package override formula wins over the
 * work's default formula, which wins over the unit&rarr;dimension fallback. When a formula applies
 * it is evaluated against the room's 14 dimensions with the evaluator's missing-variable-is-zero
 * contract (R1.4); when no formula applies the {@link VolumeFallbackResolver} maps the work's unit
 * ({@code m2}&rarr;floor area, {@code m}/{@code mb}&rarr;perimeter, {@code szt}&rarr;1, unmapped/
 * missing&rarr;0) (R5.1, R5.2, R5.4).
 *
 * <p>Feature: for-05-05-bill-of-materials, Property 3: Volume resolution follows override &rarr;
 * default &rarr; unit fallback
 *
 * <p><b>Validates: Requirements 1.4, 3.2, 3.3, 5.1, 5.2, 11.3</b>
 */
// Feature: for-05-05-bill-of-materials, Property 3: Volume resolution follows override → default → unit fallback
@Tag("Feature: for-05-05-bill-of-materials, Property 3: Volume resolution follows override → default → unit fallback")
class VolumeResolverPropertyTest {

    /**
     * Property 3: for every generated (override?, default?, unit, room) the resolved Volume follows
     * override &rarr; default &rarr; unit-fallback precedence. When a formula applies, the source is
     * {@link Source#FORMULA} and the value equals evaluating that formula against the room's present
     * dimensions (missing dimension &rarr; 0); when no formula applies, the source is
     * {@link Source#FALLBACK} and the value equals the unit&rarr;dimension mapping
     * ({@code m2}&rarr;floorArea, {@code m}/{@code mb}&rarr;perimeter, {@code szt}&rarr;1,
     * unmapped&rarr;0).
     *
     * <p>Feature: for-05-05-bill-of-materials, Property 3: Volume resolution follows override &rarr;
     * default &rarr; unit fallback
     *
     * <p><b>Validates: Requirements 1.4, 3.2, 3.3, 5.1, 5.2, 11.3</b>
     */
    @Property(tries = 200)
    @Tag("Feature: for-05-05-bill-of-materials, Property 3: Volume resolution follows override → default → unit fallback")
    void resolutionFollowsOverrideThenDefaultThenFallback(@ForAll("scenarios") Scenario scenario) {
        WorkPackageOverrideEntity override = scenario.overrideAst == null ? null : override(scenario.overrideAst);
        WorkVolumeFormulaEntity defaultFormula =
                scenario.defaultAst == null ? null : defaultFormula(scenario.defaultAst);
        RoomEntity room = scenario.toRoom();

        Resolution actual = VolumeResolver.resolve(override, defaultFormula, scenario.unitCode, room);

        // Determine the applicable formula independently: override AST -> default AST -> null.
        FormulaAst applicable = scenario.overrideAst != null ? scenario.overrideAst
                : scenario.defaultAst;

        if (applicable != null) {
            // A formula applies: FORMULA source, value equals evaluating that formula against the
            // room's present dimensions (missing dimension -> 0, per the evaluator contract, R1.4).
            assertThat(actual.source()).isEqualTo(Source.FORMULA);
            assertThat(actual.fallbackUsed()).isFalse();
            BigDecimal expected = FormulaEvaluator.evaluate(applicable, scenario.roomVars(),
                    ref -> BigDecimal.ZERO);
            assertThat(actual.volume()).isEqualByComparingTo(expected);
        } else {
            // No formula applies: FALLBACK source, value equals the unit->dimension mapping (R5).
            assertThat(actual.source()).isEqualTo(Source.FALLBACK);
            assertThat(actual.fallbackUsed()).isTrue();
            assertThat(actual.volume()).isEqualByComparingTo(scenario.expectedFallback());
        }

        // The resolved Volume is always non-null and never negative-by-construction for our inputs.
        assertThat(actual.volume()).isNotNull();
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private static WorkPackageOverrideEntity override(FormulaAst ast) {
        WorkPackageOverrideEntity entity = new WorkPackageOverrideEntity();
        entity.setOverrideParsedAst(ast);
        return entity;
    }

    private static WorkVolumeFormulaEntity defaultFormula(FormulaAst ast) {
        WorkVolumeFormulaEntity entity = new WorkVolumeFormulaEntity();
        entity.setParsedAst(ast);
        return entity;
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * A flat scenario varying the axes that matter to the precedence property: an optional override
     * AST, an optional default AST, the work's unit code (mapped, unmapped and {@code null}), and the
     * room's dimension columns (each optionally present, so the missing-variable and unset-dimension
     * paths are exercised).
     */
    private record Scenario(FormulaAst overrideAst,
                            FormulaAst defaultAst,
                            String unitCode,
                            BigDecimal floorArea,
                            BigDecimal perimeter,
                            BigDecimal wallArea,
                            Integer doorCount) {

        RoomEntity toRoom() {
            RoomEntity room = new RoomEntity();
            room.setFloorArea(floorArea);
            room.setPerimeter(perimeter);
            room.setWallArea(wallArea);
            room.setDoorCount(doorCount);
            return room;
        }

        java.util.Map<String, BigDecimal> roomVars() {
            java.util.Map<String, BigDecimal> vars = new java.util.HashMap<>();
            if (floorArea != null) {
                vars.put("floorArea", floorArea);
            }
            if (perimeter != null) {
                vars.put("perimeter", perimeter);
            }
            if (wallArea != null) {
                vars.put("wallArea", wallArea);
            }
            if (doorCount != null) {
                vars.put("doorCount", BigDecimal.valueOf(doorCount));
            }
            return vars;
        }

        /** The expected fallback Volume for this scenario's unit + room, mirroring R5.1/R5.2/R5.4. */
        BigDecimal expectedFallback() {
            if (unitCode == null) {
                return BigDecimal.ZERO;
            }
            return switch (unitCode.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "szt" -> BigDecimal.ONE;
                case "m2" -> floorArea != null ? floorArea : BigDecimal.ZERO;
                case "m", "mb" -> perimeter != null ? perimeter : BigDecimal.ZERO;
                default -> BigDecimal.ZERO;
            };
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        // Optional override / default formulas (null exercises the fall-through to the next tier).
        Arbitrary<FormulaAst> overrideAst = formulaAsts().injectNull(0.5);
        Arbitrary<FormulaAst> defaultAst = formulaAsts().injectNull(0.5);

        // Unit codes: the mapped codes (incl. both "m" and the seeded "mb"), casing/whitespace
        // variants, an unmapped code, and null — covering R5.1, R5.2, R5.4.
        Arbitrary<String> unitCode = Arbitraries.of(
                "m2", "M2", " m2 ",
                "mb", "MB",
                "m",
                "szt", "SZT",
                "kg", "unknown",
                null);

        // Dimensions: each optionally present (null exercises unset-dimension -> 0, R1.4/R5.4).
        Arbitrary<BigDecimal> money = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("10000.00"))
                .ofScale(2)
                .injectNull(0.3);
        Arbitrary<Integer> doorCount = Arbitraries.integers().between(0, 10).injectNull(0.3);

        return Combinators.combine(overrideAst, defaultAst, unitCode, money, money, money, doorCount)
                .as(Scenario::new);
    }

    /**
     * A small pool of valid formula ASTs over the room dimensions: a constant, a single variable, a
     * sum of two variables, and a product of a variable with a constant. Every leaf variable is one
     * of the 14 known room dimensions, so evaluation exercises both present and missing dimensions.
     */
    @Provide
    Arbitrary<FormulaAst> formulaAsts() {
        Arbitrary<String> varName = Arbitraries.of(
                "floorArea", "perimeter", "wallArea", "doorCount", "ceilingHeight");
        Arbitrary<BigDecimal> literal = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.5"), new BigDecimal("5.0"))
                .ofScale(1);

        Arbitrary<FormulaAst> constNode = literal.map(Const::new);
        Arbitrary<FormulaAst> varNode = varName.map(Var::new);
        Arbitrary<FormulaAst> sumNode = Combinators.combine(varName, varName)
                .as((a, b) -> new BinOp(BinOperator.ADD, new Var(a), new Var(b)));
        Arbitrary<FormulaAst> mulNode = Combinators.combine(varName, literal)
                .as((v, k) -> new BinOp(BinOperator.MUL, new Var(v), new Const(k)));

        return Arbitraries.oneOf(constNode, varNode, sumNode, mulNode);
    }
}
