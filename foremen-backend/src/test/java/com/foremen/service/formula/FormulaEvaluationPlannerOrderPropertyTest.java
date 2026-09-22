package com.foremen.service.formula;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.dao.model.formula.FormulaAst.BinOp;
import com.foremen.dao.model.formula.FormulaAst.BinOperator;
import com.foremen.dao.model.formula.FormulaAst.WorkRef;
import com.foremen.dao.model.formula.FormulaAst.WorkRefMode;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link FormulaEvaluationPlanner#planOrder(Map)} (FOR-05-04,
 * Property 4 — "Deterministic evaluation order respects references").
 *
 * <p>Formulas are built directly as {@link FormulaAst} structures (no parser involved): each
 * generated work's formula is either a bare {@link WorkRef} or a {@link BinOp} combining two
 * {@link WorkRef}s, where the referenced ref may point at another work in the same generated
 * set (a real dependency edge, Requirement 3.2) or at a ref that is deliberately absent from the
 * map (not an edge — Requirement 3.4, verified not to break planning here). The generated
 * dependency graph is constructed acyclically by only allowing a work to reference works that
 * come earlier in a fixed topological seed order, so {@code planOrder} is expected to succeed
 * (no {@code error.formula.cycle}) for every generated case.
 *
 * <p>Feature: FOR-05-04-estimate-packages-changes, Property 4
 *
 * <p><b>Validates: Requirements 3.2, 3.5</b>
 */
@Tag("Feature: FOR-05-04-estimate-packages-changes, Property 4")
class FormulaEvaluationPlannerOrderPropertyTest {

    // ------------------------------------------------------------------------------------------
    // Property 4a: planOrder returns every key exactly once (a full permutation of the input),
    // and for every edge w -> r (w's formula references r, r also a key), r appears strictly
    // before w in the result.
    // Validates: Requirement 3.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 4")
    void planOrderIsAPermutationRespectingEveryReference(@ForAll("acyclicRoomGraphs") RoomGraph graph) {
        List<String> order = FormulaEvaluationPlanner.planOrder(graph.formulas());

        // Full permutation: every key appears exactly once.
        assertThat(order).hasSize(graph.formulas().size());
        assertThat(new TreeSet<>(order)).isEqualTo(new TreeSet<>(graph.formulas().keySet()));

        // Every real edge w -> r (r is a key of the map) has r strictly before w.
        Map<String, Integer> position = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.put(order.get(i), i);
        }
        for (Map.Entry<String, Set<String>> entry : graph.edges().entrySet()) {
            String work = entry.getKey();
            for (String ref : entry.getValue()) {
                if (graph.formulas().containsKey(ref)) {
                    assertThat(position.get(ref))
                            .describedAs("edge %s -> %s: %s must precede %s", work, ref, ref, work)
                            .isLessThan(position.get(work));
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 4b: determinism (Requirement 3.5) — calling planOrder twice on the identical
    // input map, and again on a structurally-equal fresh map built with a different key
    // insertion order (including reversed), always returns the identical order. This confirms
    // the tie-break is a genuine property of the graph (ascending ref), not an artifact of the
    // map's own iteration order.
    // Validates: Requirement 3.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 4")
    void planOrderIsDeterministicRegardlessOfMapInsertionOrder(@ForAll("acyclicRoomGraphs") RoomGraph graph) {
        Map<String, FormulaAst> original = graph.formulas();

        // Same object, called twice.
        List<String> first = FormulaEvaluationPlanner.planOrder(original);
        List<String> second = FormulaEvaluationPlanner.planOrder(original);
        assertThat(second).isEqualTo(first);

        // Structurally-equal fresh map, insertion order A (sorted ascending).
        Map<String, FormulaAst> insertedAscending = new LinkedHashMap<>();
        new TreeSet<>(original.keySet()).forEach(k -> insertedAscending.put(k, original.get(k)));
        List<String> ascendingOrder = FormulaEvaluationPlanner.planOrder(insertedAscending);
        assertThat(ascendingOrder).isEqualTo(first);

        // Structurally-equal fresh map, insertion order B (reversed).
        Map<String, FormulaAst> insertedDescending = new LinkedHashMap<>();
        List<String> reversedKeys = new ArrayList<>(new TreeSet<>(original.keySet()));
        Collections.reverse(reversedKeys);
        reversedKeys.forEach(k -> insertedDescending.put(k, original.get(k)));
        List<String> descendingOrder = FormulaEvaluationPlanner.planOrder(insertedDescending);
        assertThat(descendingOrder).isEqualTo(first);
    }

    // ------------------------------------------------------------------------------------------
    // Property 4c: a reference to a work not present in the map does not appear as a constraint
    // and does not cause planning to fail (Requirement 3.2's "absent work is not an edge").
    // Validates: Requirement 3.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 4")
    void referenceToAbsentWorkIsNotAnEdgeAndDoesNotFailPlanning(
            @ForAll("acyclicRoomGraphsWithAbsentRefs") RoomGraph graph) {
        // Must not throw (no spurious cycle/failure from an absent-ref "edge").
        List<String> order = FormulaEvaluationPlanner.planOrder(graph.formulas());

        assertThat(order).hasSize(graph.formulas().size());
        assertThat(new TreeSet<>(order)).isEqualTo(new TreeSet<>(graph.formulas().keySet()));
        // None of the deliberately-absent refs leak into the returned order.
        for (String absentRef : graph.absentRefs()) {
            assertThat(order).doesNotContain(absentRef);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * A generated room's formula map plus the edges actually encoded (work -> referenced ref,
     * whether or not that ref is itself a key) and the set of refs deliberately left absent from
     * the map, so tests can assert on both without recomputing the dependency extraction.
     */
    private record RoomGraph(Map<String, FormulaAst> formulas, Map<String, Set<String>> edges,
                              Set<String> absentRefs) {
    }

    private static final List<String> SEED_REFS = List.of("X1", "X2", "X3", "X4", "X5", "X6", "X7", "X8");
    private static final List<String> ABSENT_POOL = List.of("Z90", "Z91", "Z92");

    @Provide
    Arbitrary<RoomGraph> acyclicRoomGraphs() {
        return roomSizes().flatMap(this::buildAcyclicGraph);
    }

    @Provide
    Arbitrary<RoomGraph> acyclicRoomGraphsWithAbsentRefs() {
        return roomSizes().flatMap(size -> buildAcyclicGraph(size, true));
    }

    private Arbitrary<Integer> roomSizes() {
        return Arbitraries.integers().between(1, SEED_REFS.size());
    }

    private Arbitrary<RoomGraph> buildAcyclicGraph(int size) {
        return buildAcyclicGraph(size, false);
    }

    /**
     * Builds a graph over the first {@code size} refs of {@link #SEED_REFS} (a fixed
     * topological seed order). Each work's formula is either a bare {@link WorkRef} or a
     * {@link BinOp} of two {@link WorkRef}s; every reference operand is chosen only from refs
     * that come strictly earlier in the seed order (guaranteeing acyclicity regardless of which
     * refs are chosen), or — when {@code includeAbsent} is true — optionally from a ref pool
     * that is never a key of the map.
     */
    private Arbitrary<RoomGraph> buildAcyclicGraph(int size, boolean includeAbsent) {
        List<String> refs = SEED_REFS.subList(0, size);

        Arbitrary<RoomGraph> acc = Arbitraries.just(
                new RoomGraph(new LinkedHashMap<>(), new LinkedHashMap<>(), new TreeSet<>()));

        for (int i = 0; i < refs.size(); i++) {
            String work = refs.get(i);
            Arbitrary<WorkRef> operandA = referenceOperandFor(refs, i, includeAbsent);
            Arbitrary<WorkRef> operandB = referenceOperandFor(refs, i, includeAbsent);
            Arbitrary<BinOperator> opArb = Arbitraries.of(BinOperator.values());

            // Either a bare WorkRef (one operand) or a BinOp of two operands.
            Arbitrary<FormulaAst> bareFormula = operandA.map(a -> (FormulaAst) a);
            Arbitrary<FormulaAst> binOpFormula = opArb.flatMap(op -> operandA.flatMap(a ->
                    operandB.map(b -> (FormulaAst) new BinOp(op, a, b))));
            Arbitrary<FormulaAst> formulaArb = Arbitraries.oneOf(bareFormula, binOpFormula);

            acc = acc.flatMap(partial -> formulaArb.map(formula -> {
                Set<String> refsUsed = new TreeSet<>();
                collectWorkRefsForTest(formula, refsUsed);

                Map<String, FormulaAst> formulas = new LinkedHashMap<>(partial.formulas());
                formulas.put(work, formula);

                Map<String, Set<String>> edges = new LinkedHashMap<>(partial.edges());
                edges.put(work, refsUsed);

                Set<String> absentRefs = new TreeSet<>(partial.absentRefs());
                for (String r : refsUsed) {
                    if (!refs.contains(r)) {
                        absentRefs.add(r);
                    }
                }

                return new RoomGraph(formulas, edges, absentRefs);
            }));
        }
        return acc;
    }

    /** Collects every {@link WorkRef#ref()} found anywhere inside {@code node} (test-local copy). */
    private static void collectWorkRefsForTest(FormulaAst node, Set<String> out) {
        if (node instanceof WorkRef workRef) {
            out.add(workRef.ref());
        } else if (node instanceof BinOp binOp) {
            collectWorkRefsForTest(binOp.l(), out);
            collectWorkRefsForTest(binOp.r(), out);
        }
    }

    /**
     * An arbitrary {@link WorkRef} operand for the work at {@code index} in {@code refs}: chosen
     * from refs strictly earlier than {@code index} (guaranteeing the resulting graph is
     * acyclic), or — if {@code index == 0} (no earlier refs) — the work references itself is
     * disallowed by falling back to an absent ref when {@code includeAbsent}, else a earlier-only
     * pool that always contains at least the trivial self-safe absent fallback.
     */
    private Arbitrary<WorkRef> referenceOperandFor(List<String> refs, int index, boolean includeAbsent) {
        List<String> earlier = refs.subList(0, index);
        List<String> pool = new ArrayList<>(earlier);
        if (includeAbsent || earlier.isEmpty()) {
            pool.addAll(ABSENT_POOL);
        }
        if (pool.isEmpty()) {
            pool = ABSENT_POOL;
        }
        Arbitrary<String> refArb = Arbitraries.of(pool);
        Arbitrary<WorkRefMode> modeArb = Arbitraries.of(WorkRefMode.values());
        return refArb.flatMap(ref -> modeArb.map(mode -> new WorkRef(ref, mode)));
    }
}
