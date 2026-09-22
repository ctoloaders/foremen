package com.foremen.service.formula;

import java.util.Set;

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
 * Validates a parsed {@link FormulaAst} against the formula language's semantic constraints
 * (FOR-05-04 §Components Component 4, §6.2 {@code parseAndValidate}, task 8.2).
 *
 * <p>{@link FormulaParser} only checks grammar (task 8.1); this validator performs the semantic
 * checks the parser deliberately leaves out:
 * <ul>
 *   <li>every {@link Var} name is one of the 14 room dimensions
 *       ({@link FormulaAst#ROOM_DIMENSION_VARS}), else {@code 400 error.formula.unknown.variable}
 *       (Requirement 2.2);</li>
 *   <li>every {@link WorkRef} resolves to a known work reference, else
 *       {@code 400 error.formula.unknown.work.reference} (Requirement 2.2, 3.1);</li>
 *   <li>operators/arities are legal — structurally guaranteed by the AST's Java types
 *       ({@link BinOp} always has exactly two operands, {@link Count} always has a subject/cmp/
 *       value triple, etc.), so this validator's role for that check is to walk into every
 *       nested sub-expression so a nested illegal {@link Var}/{@link WorkRef} is also caught
 *       (Requirement 2.3, 2.4).</li>
 * </ul>
 *
 * <p>Pure and stateless: validating the same {@code (ast, knownWorkRefs)} pair always produces
 * the same outcome, and validation has no side effects other than throwing on invalid input.
 */
public final class FormulaValidator {

    private FormulaValidator() {
    }

    /**
     * Validates {@code ast} against the 14 room-dimension variables and {@code knownWorkRefs}.
     *
     * @param ast            the parsed formula to validate
     * @param knownWorkRefs  the set of work references ({@code WorkRef.ref()} values) considered
     *                       resolvable in the formula's context (e.g. the room's other work
     *                       references, or the catalog's known cell/work refs)
     * @throws ForemenApiException {@code 400 error.formula.unknown.variable} if a {@link Var}
     *                              node's name is not one of the 14 room dimensions
     * @throws ForemenApiException {@code 400 error.formula.unknown.work.reference} if a
     *                              {@link WorkRef} node's {@code ref} is not in
     *                              {@code knownWorkRefs}
     */
    public static void validate(FormulaAst ast, Set<String> knownWorkRefs) {
        validateNode(ast, knownWorkRefs);
    }

    private static void validateNode(FormulaAst node, Set<String> knownWorkRefs) {
        if (node == null) {
            return;
        }

        if (node instanceof Const) {
            return;
        }

        if (node instanceof Var variable) {
            if (!FormulaAst.ROOM_DIMENSION_VARS.contains(variable.name())) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                        "error.formula.unknown.variable", variable.name());
            }
            return;
        }

        if (node instanceof WorkRef workRef) {
            if (!knownWorkRefs.contains(workRef.ref())) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                        "error.formula.unknown.work.reference", workRef.ref());
            }
            return;
        }

        if (node instanceof BinOp binOp) {
            validateNode(binOp.l(), knownWorkRefs);
            validateNode(binOp.r(), knownWorkRefs);
            return;
        }

        if (node instanceof Cond cond) {
            for (CondCase condCase : cond.cases()) {
                validateComparison(condCase.when(), knownWorkRefs);
                validateNode(condCase.then(), knownWorkRefs);
            }
            validateNode(cond.elseExpr(), knownWorkRefs);
            return;
        }

        if (node instanceof Count count) {
            validateNode(count.subject(), knownWorkRefs);
            validateNode(count.value(), knownWorkRefs);
            return;
        }

        // Exhaustive over the sealed FormulaAst hierarchy; unreachable.
        throw new IllegalStateException("Unhandled FormulaAst node: " + node.getClass());
    }

    private static void validateComparison(Comparison comparison, Set<String> knownWorkRefs) {
        validateNode(comparison.left(), knownWorkRefs);
        validateNode(comparison.right(), knownWorkRefs);
    }
}
