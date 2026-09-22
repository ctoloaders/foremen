package com.foremen.service.formula;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;

import org.springframework.http.HttpStatus;

import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.dao.model.formula.FormulaAst.BinOp;
import com.foremen.dao.model.formula.FormulaAst.CompareOperator;
import com.foremen.dao.model.formula.FormulaAst.Comparison;
import com.foremen.dao.model.formula.FormulaAst.Cond;
import com.foremen.dao.model.formula.FormulaAst.CondCase;
import com.foremen.dao.model.formula.FormulaAst.Const;
import com.foremen.dao.model.formula.FormulaAst.Count;
import com.foremen.dao.model.formula.FormulaAst.Var;
import com.foremen.dao.model.formula.FormulaAst.WorkRef;
import com.foremen.dao.model.formula.FormulaAst.WorkRefMode;
import com.foremen.exception.ForemenApiException;

/**
 * Evaluates a parsed {@link FormulaAst} against a room's dimension variables and already-resolved
 * cross-work volumes (FOR-05-04 §Components Component 4, §6.4 {@code evaluate} semantics, task 8.7).
 *
 * <p>Node semantics (Requirements 3.1, 3.4, 3.5):
 * <ul>
 *   <li>{@link Const} — evaluates to its literal value.</li>
 *   <li>{@link Var} — evaluates to {@code vars.get(name)}; a missing/null dimension evaluates
 *       to {@link BigDecimal#ZERO} rather than throwing (a room may leave a dimension unset).</li>
 *   <li>{@link WorkRef} with {@link WorkRefMode#VOLUME} — delegates to {@code resolvedVolumeOf},
 *       which the caller supplies already resolved in evaluation order (Requirement 3.2); an
 *       absent/not-present work resolves to zero there (Requirement 3.4), not here.</li>
 *   <li>{@link WorkRef} with {@link WorkRefMode#PRESENT} — {@code 1} if the resolved volume is
 *       greater than zero, else {@code 0}.</li>
 *   <li>{@link BinOp} — arithmetic on the recursively evaluated operands; division by zero throws
 *       {@code 400 error.formula.division.by.zero} rather than propagating infinity/NaN.</li>
 *   <li>{@link Cond} — the first {@link CondCase} whose {@code when} comparison holds contributes
 *       its {@code then} value; if none match, the optional {@code else} expression, else zero.</li>
 *   <li>{@link Count} — {@code 1} if {@code subject cmp value} holds, else {@code 0}.</li>
 * </ul>
 *
 * <p>Pure and deterministic: evaluating the same {@code (ast, vars, resolvedVolumeOf)} twice
 * always yields an equal result, no inputs are mutated, and there is no I/O.
 */
public final class FormulaEvaluator {

    private FormulaEvaluator() {
    }

    /**
     * Evaluates {@code ast} against {@code vars} (room-dimension variables) and
     * {@code resolvedVolumeOf} (already-resolved cross-work volumes for the same room).
     *
     * @param ast              the parsed formula to evaluate
     * @param vars             room-dimension variable values, keyed by {@link Var#name()}; a
     *                         missing or null entry is treated as {@link BigDecimal#ZERO}
     * @param resolvedVolumeOf resolves a {@link WorkRef} node to that work's already-computed
     *                         volume for the room (the caller decides what "absent" resolves to,
     *                         per Requirement 3.4)
     * @throws ForemenApiException {@code 400 error.formula.division.by.zero} if a {@link BinOp}
     *                              division's divisor evaluates to zero
     */
    public static BigDecimal evaluate(FormulaAst ast, Map<String, BigDecimal> vars,
                                       Function<WorkRef, BigDecimal> resolvedVolumeOf) {
        if (ast instanceof Const constNode) {
            return constNode.v();
        }

        if (ast instanceof Var variable) {
            BigDecimal value = vars.get(variable.name());
            return value != null ? value : BigDecimal.ZERO;
        }

        if (ast instanceof WorkRef workRef) {
            BigDecimal volume = resolvedVolumeOf.apply(workRef);
            if (workRef.mode() == WorkRefMode.PRESENT) {
                return volume != null && volume.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
            }
            return volume != null ? volume : BigDecimal.ZERO;
        }

        if (ast instanceof BinOp binOp) {
            return evaluateBinOp(binOp, vars, resolvedVolumeOf);
        }

        if (ast instanceof Cond cond) {
            for (CondCase condCase : cond.cases()) {
                if (evaluateComparison(condCase.when(), vars, resolvedVolumeOf)) {
                    return evaluate(condCase.then(), vars, resolvedVolumeOf);
                }
            }
            return cond.elseExpr() != null
                    ? evaluate(cond.elseExpr(), vars, resolvedVolumeOf)
                    : BigDecimal.ZERO;
        }

        if (ast instanceof Count count) {
            BigDecimal subject = evaluate(count.subject(), vars, resolvedVolumeOf);
            BigDecimal value = evaluate(count.value(), vars, resolvedVolumeOf);
            return compare(subject, count.cmp(), value) ? BigDecimal.ONE : BigDecimal.ZERO;
        }

        // Exhaustive over the sealed FormulaAst hierarchy; unreachable.
        throw new IllegalStateException("Unhandled FormulaAst node: " + ast.getClass());
    }

    private static BigDecimal evaluateBinOp(BinOp binOp, Map<String, BigDecimal> vars,
                                             Function<WorkRef, BigDecimal> resolvedVolumeOf) {
        BigDecimal left = evaluate(binOp.l(), vars, resolvedVolumeOf);
        BigDecimal right = evaluate(binOp.r(), vars, resolvedVolumeOf);

        return switch (binOp.op()) {
            case ADD -> left.add(right);
            case SUB -> left.subtract(right);
            case MUL -> left.multiply(right);
            case DIV -> {
                if (right.compareTo(BigDecimal.ZERO) == 0) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.formula.division.by.zero");
                }
                yield left.divide(right, java.math.MathContext.DECIMAL64);
            }
        };
    }

    private static boolean evaluateComparison(Comparison comparison, Map<String, BigDecimal> vars,
                                               Function<WorkRef, BigDecimal> resolvedVolumeOf) {
        BigDecimal left = evaluate(comparison.left(), vars, resolvedVolumeOf);
        BigDecimal right = evaluate(comparison.right(), vars, resolvedVolumeOf);
        return compare(left, comparison.cmp(), right);
    }

    private static boolean compare(BigDecimal left, CompareOperator cmp, BigDecimal right) {
        int c = left.compareTo(right);
        return switch (cmp) {
            case EQ -> c == 0;
            case NEQ -> c != 0;
            case LT -> c < 0;
            case LTE -> c <= 0;
            case GT -> c > 0;
            case GTE -> c >= 0;
        };
    }
}
