package com.foremen.service.formula;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.http.HttpStatus;

import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.dao.model.formula.FormulaAst.BinOp;
import com.foremen.dao.model.formula.FormulaAst.Comparison;
import com.foremen.dao.model.formula.FormulaAst.Cond;
import com.foremen.dao.model.formula.FormulaAst.CondCase;
import com.foremen.dao.model.formula.FormulaAst.Const;
import com.foremen.dao.model.formula.FormulaAst.Count;
import com.foremen.dao.model.formula.FormulaAst.Var;
import com.foremen.dao.model.formula.FormulaAst.WorkRef;
import com.foremen.exception.ForemenApiException;

/**
 * Plans the deterministic evaluation order for a room's set of work volume formulas, and detects
 * circular cross-work references (FOR-05-04 §Components Component 4, §6.3 {@code planOrder},
 * task 8.4).
 *
 * <p>Given {@code roomFormulas} — a map of work reference ({@code WorkRef.ref()} string, e.g.
 * {@code "X28"}) to that work's parsed formula for works present in a room — this builds the
 * dependency graph {@code w -> r} for every {@link WorkRef} operand {@code r} found inside
 * {@code roomFormulas[w]} that is itself a key of {@code roomFormulas} (Requirement 3.2). A
 * reference to a work ref that is <b>not</b> a key of {@code roomFormulas} (i.e. not present in
 * the room) is deliberately <b>not</b> an edge — it resolves to {@code 0} at evaluation time
 * instead (Requirement 3.4), so it plays no part in ordering or cycle detection here.
 *
 * <p>The graph is topologically sorted with Kahn's algorithm, breaking ties among multiple
 * ready (zero remaining incoming edge) nodes by <b>ascending work ref</b> so that the same input
 * map always yields the same order (Requirement 3.5). If the graph cannot be fully ordered (a
 * back-edge — a direct or transitive cycle), the planner throws
 * {@code 409 error.formula.cycle} listing the refs of the works still involved in the cycle
 * (Requirement 3.3), so a cyclical formula is rejected at write time rather than persisted.
 *
 * <p>Pure and stateless: planning the same {@code roomFormulas} map always produces the same
 * order (or the same cycle failure).
 */
public final class FormulaEvaluationPlanner {

    private FormulaEvaluationPlanner() {
    }

    /**
     * Computes the deterministic, dependency-respecting evaluation order for {@code roomFormulas}.
     *
     * @param roomFormulas map of work ref to that work's parsed formula, for every work present
     *                     in the room whose volume is formula-driven
     * @return the work refs in an order such that, for every cross-work reference {@code w -> r}
     *         where both {@code w} and {@code r} are keys of {@code roomFormulas}, {@code r}
     *         precedes {@code w}; ties among independently-ready refs break by ascending ref
     * @throws ForemenApiException {@code 409 error.formula.cycle} if the refs involved in
     *                              {@code roomFormulas} form a direct or transitive cycle; the
     *                              message params list the involved work refs in ascending order
     */
    public static List<String> planOrder(Map<String, FormulaAst> roomFormulas) {
        if (roomFormulas == null || roomFormulas.isEmpty()) {
            return List.of();
        }

        Set<String> knownRefs = roomFormulas.keySet();

        // adjacency: w -> set of r such that w's formula references r (edge w -> r means
        // "r must be evaluated before w").
        Map<String, Set<String>> dependsOn = new TreeMap<>();
        // inDegree here counts, for each node, how many *other* nodes depend on it being
        // resolved first is irrelevant for Kahn's algorithm keyed on "remaining prerequisites";
        // instead track remaining unresolved dependency count per node.
        Map<String, Integer> remainingDeps = new TreeMap<>();
        for (String ref : knownRefs) {
            remainingDeps.put(ref, 0);
        }

        for (Map.Entry<String, FormulaAst> entry : roomFormulas.entrySet()) {
            String work = entry.getKey();
            Set<String> refs = new TreeSet<>();
            collectWorkRefs(entry.getValue(), refs);
            // Only a reference to a work ref that is itself present in the room counts as an
            // edge (Requirement 3.2); a ref to an absent work is not an edge (Requirement 3.4).
            // A self-reference (work -> work) is a legitimate 1-node cycle and is kept so it is
            // caught by cycle detection below.
            refs.retainAll(knownRefs);
            dependsOn.put(work, refs);
            remainingDeps.put(work, refs.size());
        }

        // reverse adjacency: r -> set of w that depend on r, used to decrement remainingDeps
        // once r has been placed in the order.
        Map<String, Set<String>> dependents = new TreeMap<>();
        for (String ref : knownRefs) {
            dependents.put(ref, new TreeSet<>());
        }
        for (Map.Entry<String, Set<String>> entry : dependsOn.entrySet()) {
            String work = entry.getKey();
            for (String dep : entry.getValue()) {
                dependents.get(dep).add(work);
            }
        }

        TreeSet<String> ready = new TreeSet<>();
        for (Map.Entry<String, Integer> entry : remainingDeps.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<String> order = new ArrayList<>(knownRefs.size());
        Map<String, Integer> remaining = new LinkedHashMap<>(remainingDeps);
        while (!ready.isEmpty()) {
            String next = ready.pollFirst();
            order.add(next);
            for (String dependent : dependents.get(next)) {
                int updated = remaining.get(dependent) - 1;
                remaining.put(dependent, updated);
                if (updated == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (order.size() < knownRefs.size()) {
            List<String> involved = new ArrayList<>();
            for (String ref : knownRefs) {
                if (!order.contains(ref)) {
                    involved.add(ref);
                }
            }
            involved.sort(String::compareTo);
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.formula.cycle",
                    (Object[]) involved.toArray(new String[0]));
        }

        return order;
    }

    /** Collects every {@link WorkRef#ref()} found anywhere inside {@code node}. */
    private static void collectWorkRefs(FormulaAst node, Set<String> out) {
        if (node == null) {
            return;
        }
        if (node instanceof Const) {
            return;
        }
        if (node instanceof Var) {
            return;
        }
        if (node instanceof WorkRef workRef) {
            out.add(workRef.ref());
            return;
        }
        if (node instanceof BinOp binOp) {
            collectWorkRefs(binOp.l(), out);
            collectWorkRefs(binOp.r(), out);
            return;
        }
        if (node instanceof Cond cond) {
            for (CondCase condCase : cond.cases()) {
                collectComparisonRefs(condCase.when(), out);
                collectWorkRefs(condCase.then(), out);
            }
            collectWorkRefs(cond.elseExpr(), out);
            return;
        }
        if (node instanceof Count count) {
            collectWorkRefs(count.subject(), out);
            collectWorkRefs(count.value(), out);
            return;
        }
        throw new IllegalStateException("Unhandled FormulaAst node: " + node.getClass());
    }

    private static void collectComparisonRefs(Comparison comparison, Set<String> out) {
        collectWorkRefs(comparison.left(), out);
        collectWorkRefs(comparison.right(), out);
    }
}
