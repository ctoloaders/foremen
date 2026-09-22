package com.foremen.service.formula;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.exception.ForemenApiException;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for the {@link FormulaParser}/{@link FormulaValidator} round-trip
 * (FOR-05-04, design §Correctness Properties, Property 3 — "Formula parse/validate round-trip").
 *
 * <p>Per the design statement: <i>"For any formula in the supported grammar, {@code parse} then
 * serialising the AST and re-loading it yields an AST that evaluates identically to the
 * original; every accepted AST uses only the 14 dimension variables and known work
 * references."</i>
 *
 * <p>This class generates simple, syntactically valid source-text formulas (arithmetic over
 * {@code Var}/{@code WorkRef}/constants, plus {@code IFS} and {@code COUNTIF} combinations)
 * rather than building {@link FormulaAst} trees directly, because the property is explicitly
 * about the {@code parse} → serialize → reload → re-evaluate round trip starting from source
 * text (Requirements 2.3, 2.4, 2.6) — the same entry point {@code WorkVolumeFormulaEntity} and
 * {@code WorkPackageOverrideEntity} use on write.
 *
 * <p>No Spring context and no database — {@link FormulaParser}, {@link FormulaValidator}, and
 * {@link FormulaEvaluator} are pure static utilities exercised directly, and JSON round-trip
 * uses a plain {@link ObjectMapper} (matching {@code FormulaAstJsonRoundTripTest}, task 7.2).
 *
 * <p>Feature: FOR-05-04-estimate-packages-changes, Property 3
 *
 * <p><b>Validates: Requirements 2.3, 2.4, 2.6</b>
 */
@Tag("Feature: FOR-05-04-estimate-packages-changes, Property 3")
class FormulaParserValidatorRoundTripPropertyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> DIMENSION_VARS = List.copyOf(FormulaAst.ROOM_DIMENSION_VARS);
    private static final List<String> KNOWN_WORK_REFS = List.of("X26", "X27", "X28", "AL33", "AL48");

    // ------------------------------------------------------------------------------------------
    // Property 3a: parsing a syntactically valid formula (Var names drawn from the 14 room
    // dimensions, WorkRefs drawn from a known set) succeeds and the resulting AST passes
    // FormulaValidator.validate without throwing.
    // Validates: Requirements 2.3, 2.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 3")
    void parsingValidFormulaProducesAnAstThatValidatorAccepts(@ForAll("validFormulaSources") String source) {
        FormulaAst ast = FormulaParser.parse(source);

        assertThat(ast).isNotNull();

        // Should not throw: every Var is one of the 14 dimensions, every WorkRef is known.
        FormulaValidator.validate(ast, Set.copyOf(KNOWN_WORK_REFS));
    }

    // ------------------------------------------------------------------------------------------
    // Property 3b: parsing an out-of-grammar formula always throws
    // error.formula.illegal.operator (round-trip contract: invalid source never becomes a
    // persistable AST).
    // Validates: Requirements 2.3, 2.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 3")
    void parsingIllegalFormulaAlwaysThrowsIllegalOperator(@ForAll("illegalFormulaSources") String source) {
        assertThatThrownBy(() -> FormulaParser.parse(source))
                .isInstanceOf(ForemenApiException.class)
                .hasFieldOrPropertyWithValue("messageCode", "error.formula.illegal.operator");
    }

    // ------------------------------------------------------------------------------------------
    // Property 3c: parse(source) → serialize the AST to JSON → deserialize it back yields an AST
    // that equals the directly-parsed AST (jsonb persistence round-trip, tying together task
    // 7.1/7.2's JSON round-trip with the parser's output).
    // Validates: Requirement 2.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 3")
    void parsedAstSurvivesJsonSerializationRoundTrip(@ForAll("validFormulaSources") String source)
            throws Exception {
        FormulaAst directlyParsed = FormulaParser.parse(source);

        String json = objectMapper.writeValueAsString(directlyParsed);
        FormulaAst reloaded = objectMapper.readValue(json, FormulaAst.class);

        assertThat(reloaded).isEqualTo(directlyParsed);
    }

    // ------------------------------------------------------------------------------------------
    // Property 3d: the parse → JSON round trip AST evaluates identically to the directly-parsed
    // AST for any assignment of room-dimension variables and resolved work-ref volumes.
    // Validates: Requirement 2.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 3")
    void parsedAndReloadedAstEvaluateIdentically(
            @ForAll("validFormulaSources") String source,
            @ForAll("roomVariableAssignments") Map<String, BigDecimal> vars,
            @ForAll("workRefVolumeAssignments") Map<String, BigDecimal> workRefVolumes) throws Exception {
        FormulaAst directlyParsed = FormulaParser.parse(source);

        String json = objectMapper.writeValueAsString(directlyParsed);
        FormulaAst reloaded = objectMapper.readValue(json, FormulaAst.class);

        // Division by zero is a data-dependent evaluator error, not a round-trip property of the
        // AST itself; skip assignments that would trip it for either AST evaluation (they trip
        // identically anyway, since the ASTs are structurally equal — this simply avoids an
        // exception aborting the property before the equality assertion runs).
        BigDecimal directResult;
        try {
            directResult = FormulaEvaluator.evaluate(directlyParsed, vars,
                    workRef -> workRefVolumes.getOrDefault(workRef.ref(), BigDecimal.ZERO));
        } catch (ForemenApiException e) {
            return; // division by zero — not part of this property's scope
        }

        BigDecimal reloadedResult = FormulaEvaluator.evaluate(reloaded, vars,
                workRef -> workRefVolumes.getOrDefault(workRef.ref(), BigDecimal.ZERO));

        assertThat(reloadedResult).isEqualByComparingTo(directResult);
    }

    // ------------------------------------------------------------------------------------------
    // Example: a handful of concrete formulas covering arithmetic + Var + WorkRef + IFS +
    // COUNTIF combinations, to pin down the shape independent of the generators above.
    // Validates: Requirements 2.3, 2.4, 2.6
    // ------------------------------------------------------------------------------------------

    @Test
    void concreteFormulaCombinationsRoundTripAndValidate() throws Exception {
        List<String> examples = List.of(
                "2",
                "floorArea",
                "X28",
                "floorArea*2",
                "(wallArea-doorArea)*ceilingHeight/2",
                "IFS(AL33=1,3,AL33=2,6,10)",
                "IFS(floorArea>10,X28,X26)",
                "COUNTIF(floorArea,>,10)",
                "COUNTIF(X28,=,0)+floorArea*perimeter"
        );

        for (String source : examples) {
            FormulaAst ast = FormulaParser.parse(source);
            FormulaValidator.validate(ast, Set.copyOf(KNOWN_WORK_REFS));

            String json = objectMapper.writeValueAsString(ast);
            FormulaAst reloaded = objectMapper.readValue(json, FormulaAst.class);

            assertThat(reloaded).as("source=%s", source).isEqualTo(ast);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> validFormulaSources() {
        return Arbitraries.oneOf(
                constantSources(),
                varSources(),
                workRefSources(),
                arithmeticSources(),
                ifsSources(),
                countifSources()
        );
    }

    private Arbitrary<String> constantSources() {
        return Arbitraries.integers().between(0, 500).map(String::valueOf);
    }

    private Arbitrary<String> varSources() {
        return Arbitraries.of(DIMENSION_VARS);
    }

    private Arbitrary<String> workRefSources() {
        return Arbitraries.of(KNOWN_WORK_REFS);
    }

    /** Simple binary arithmetic combining two atoms (const/var/workRef) with one operator. */
    private Arbitrary<String> arithmeticSources() {
        Arbitrary<String> atom = Arbitraries.oneOf(constantSources(), varSources(), workRefSources());
        Arbitrary<String> op = Arbitraries.of("+", "-", "*", "/");
        return Combinators.combine(atom, op, atom).as((l, o, r) -> "(" + l + o + r + ")");
    }

    /** IFS(var CMP const, then, else) covering the conditional grammar. */
    private Arbitrary<String> ifsSources() {
        Arbitrary<String> var = varSources();
        Arbitrary<String> cmp = Arbitraries.of("=", "<>", "<", "<=", ">", ">=");
        Arbitrary<String> constant = constantSources();
        Arbitrary<String> thenExpr = Arbitraries.oneOf(constantSources(), workRefSources());
        Arbitrary<String> elseExpr = Arbitraries.oneOf(constantSources(), workRefSources());

        return Combinators.combine(var, cmp, constant, thenExpr, elseExpr)
                .as((v, c, k, then, els) -> "IFS(" + v + c + k + "," + then + "," + els + ")");
    }

    /** COUNTIF(var, CMP, const) covering the boolean-presence-test grammar. */
    private Arbitrary<String> countifSources() {
        Arbitrary<String> var = varSources();
        Arbitrary<String> cmp = Arbitraries.of("=", "<>", "<", "<=", ">", ">=");
        Arbitrary<String> constant = constantSources();

        return Combinators.combine(var, cmp, constant)
                .as((v, c, k) -> "COUNTIF(" + v + "," + c + "," + k + ")");
    }

    @Provide
    Arbitrary<String> illegalFormulaSources() {
        return Arbitraries.of(
                "2+",
                "*3",
                "(floorArea",
                "floorArea))",
                "2 ^ 3",
                "IFS()",
                "IFS(floorArea>10)",
                "COUNTIF(floorArea)",
                "COUNTIF(floorArea,~,10)",
                "UNKNOWNFN(1,2)",
                "@floorArea",
                "floorArea..2",
                ",",
                "IFS(floorArea>10,3,)"
        );
    }

    @Provide
    Arbitrary<Map<String, BigDecimal>> roomVariableAssignments() {
        return valueAssignments(DIMENSION_VARS);
    }

    @Provide
    Arbitrary<Map<String, BigDecimal>> workRefVolumeAssignments() {
        return valueAssignments(KNOWN_WORK_REFS);
    }

    /** Builds a generator of {@code name -> value} maps, one random value per name in {@code names}. */
    private Arbitrary<Map<String, BigDecimal>> valueAssignments(List<String> names) {
        Arbitrary<BigDecimal> value = Arbitraries.integers().between(0, 200)
                .map(i -> BigDecimal.valueOf(i, 0));

        List<Arbitrary<Map.Entry<String, BigDecimal>>> entryArbitraries = new ArrayList<>();
        for (String name : names) {
            entryArbitraries.add(value.map(v -> Map.entry(name, v)));
        }

        return Combinators.combine(entryArbitraries).as(entries -> {
            Map<String, BigDecimal> map = new HashMap<>();
            for (Map.Entry<String, BigDecimal> entry : entries) {
                map.put(entry.getKey(), entry.getValue());
            }
            return map;
        });
    }
}
