package com.foremen.service.formula;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.dao.model.formula.FormulaAst.Const;
import com.foremen.dao.model.formula.FormulaAst.WorkRef;
import com.foremen.dao.model.formula.FormulaAst.WorkRefMode;
import com.foremen.exception.ForemenApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for {@link FormulaEvaluationPlanner#planOrder(Map)} (FOR-05-04,
 * Property 5 — "Cycle rejection at save").
 *
 * <p>For any set of room formulas containing a direct or transitive cycle among the
 * {@link WorkRef} cross-references, {@code planOrder} must reject the whole set with
 * {@code 409 error.formula.cycle} rather than silently ordering a subset or persisting a
 * partial plan (Requirement 3.3). These tests build the cyclic dependency graphs directly as
 * {@link FormulaAst} structures (a work's formula is a single {@link WorkRef} operand pointing
 * at the next work in the cycle) and assert the planner always throws the documented exception
 * shape, that the involved works reported in the exception are drawn from the true cycle
 * membership, and that mixing in extra unrelated acyclic works does not let the cycle slip
 * through.
 *
 * <p>No Spring context and no database — {@code planOrder} is a pure static method exercised
 * over in-memory maps.
 *
 * <p>Feature: FOR-05-04-estimate-packages-changes, Property 5
 *
 * <p><b>Validates: Requirement 3.3</b>
 */
@Tag("Feature: FOR-05-04-estimate-packages-changes, Property 5")
class FormulaEvaluationPlannerCycleRejectionPropertyTest {

    // ------------------------------------------------------------------------------------------
    // Property 5a: any cycle of length 2..6 (mutual/transitive references among a random subset
    // of the generated work refs) is rejected with 409 error.formula.cycle, and the reported
    // work refs are a non-empty subset of the true cycle membership.
    // Validates: Requirement 3.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 5")
    void cyclicRoomFormulasAreRejectedWithCycleError(@ForAll("cyclicWorkRefChains") List<String> cycleRefs) {
        Map<String, FormulaAst> roomFormulas = buildCycle(cycleRefs);

        assertThatThrownBy(() -> FormulaEvaluationPlanner.planOrder(roomFormulas))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException apiEx = (ForemenApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getMessageCode()).isEqualTo("error.formula.cycle");

                    Set<String> reportedRefs = Set.of(
                            java.util.Arrays.stream(apiEx.getMessageParams())
                                    .map(Object::toString)
                                    .toArray(String[]::new));
                    assertThat(reportedRefs).isNotEmpty();
                    // Every reported ref must actually be a member of the cyclic set — the
                    // planner never invents a ref that wasn't part of roomFormulas.
                    assertThat(reportedRefs).isSubsetOf(new LinkedHashSet<>(cycleRefs));
                    // The reported refs are exactly the works that never made it into a valid
                    // topological position, i.e. the true cycle membership.
                    assertThat(reportedRefs).isEqualTo(new LinkedHashSet<>(cycleRefs));
                });
    }

    // ------------------------------------------------------------------------------------------
    // Property 5b: edge case — a single work whose own formula references itself is a trivial
    // 1-node cycle and is rejected the same way.
    // Validates: Requirement 3.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 5")
    void selfReferencingWorkIsRejectedAsTrivialCycle(@ForAll("workRefName") String selfRef) {
        Map<String, FormulaAst> roomFormulas = new LinkedHashMap<>();
        roomFormulas.put(selfRef, new WorkRef(selfRef, WorkRefMode.VOLUME));

        assertThatThrownBy(() -> FormulaEvaluationPlanner.planOrder(roomFormulas))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException apiEx = (ForemenApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getMessageCode()).isEqualTo("error.formula.cycle");
                    assertThat(apiEx.getMessageParams()).contains(selfRef);
                });
    }

    // ------------------------------------------------------------------------------------------
    // Property 5c: a cycle among a subset of the works still rejects the whole set even when
    // extra, unrelated acyclic works (no refs, or refs only to constants) are mixed in — the
    // planner must not silently succeed by only considering the acyclic subset.
    // Validates: Requirement 3.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 5")
    void cycleAmongSubsetStillRejectsWholeSetWithExtraAcyclicWorks(
            @ForAll("cyclicWorkRefChains") List<String> cycleRefs,
            @ForAll("disjointAcyclicWorkRefs") List<String> extraRefs) {

        Map<String, FormulaAst> roomFormulas = buildCycle(cycleRefs);
        // Extra acyclic works: each has a constant-only formula, no cross-references at all.
        for (String extra : extraRefs) {
            if (!roomFormulas.containsKey(extra)) {
                roomFormulas.put(extra, new Const(java.math.BigDecimal.ONE));
            }
        }

        // Guard against the (rare) generator collision where an "extra" ref coincides with a
        // cycle ref, which would shrink the effective cycle set.
        Set<String> cycleSet = new LinkedHashSet<>(cycleRefs);

        assertThatThrownBy(() -> FormulaEvaluationPlanner.planOrder(roomFormulas))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException apiEx = (ForemenApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getMessageCode()).isEqualTo("error.formula.cycle");

                    Set<String> reportedRefs = Set.of(
                            java.util.Arrays.stream(apiEx.getMessageParams())
                                    .map(Object::toString)
                                    .toArray(String[]::new));
                    // The reported cycle membership is exactly the cyclic subset — the acyclic
                    // extras must never appear as "involved" and must never mask the cycle.
                    assertThat(reportedRefs).isEqualTo(cycleSet);
                    assertThat(reportedRefs).isSubsetOf(cycleSet);
                });
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Builds a room formula map where each ref in {@code cycleRefs} has a formula that is a
     * single {@link WorkRef} pointing at the next ref in the list, wrapping around at the end —
     * i.e. a ring of length {@code cycleRefs.size()} (2..6), or (via the self-ref test) a
     * 1-node ring.
     */
    private static Map<String, FormulaAst> buildCycle(List<String> cycleRefs) {
        Map<String, FormulaAst> roomFormulas = new LinkedHashMap<>();
        int n = cycleRefs.size();
        for (int i = 0; i < n; i++) {
            String work = cycleRefs.get(i);
            String next = cycleRefs.get((i + 1) % n);
            roomFormulas.put(work, new WorkRef(next, WorkRefMode.VOLUME));
        }
        return roomFormulas;
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** A syntactically plausible work/cell reference, e.g. "X7", "AL42". */
    @Provide
    Arbitrary<String> workRefName() {
        Arbitrary<String> letters = Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(2);
        Arbitrary<Integer> digits = Arbitraries.integers().between(1, 999);
        return Combinators.combine(letters, digits).as((l, d) -> l + d);
    }

    /**
     * A list of 2..6 distinct work refs forming the membership of a direct (size 2) or
     * transitive (size 3..6) cycle.
     */
    @Provide
    Arbitrary<List<String>> cyclicWorkRefChains() {
        return workRefName()
                .list()
                .ofMinSize(2)
                .ofMaxSize(6)
                .uniqueElements()
                .map(ArrayList::new);
    }

    /**
     * A small list of distinct, extra work refs disjoint in spirit from the cycle (collision
     * with the cycle set is tolerated and explicitly guarded against in the property itself).
     */
    @Provide
    Arbitrary<List<String>> disjointAcyclicWorkRefs() {
        return workRefName()
                .list()
                .ofMinSize(0)
                .ofMaxSize(4)
                .uniqueElements()
                .map(ArrayList::new);
    }
}
