package com.foremen.service.formula;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.exception.ForemenApiException;

/**
 * FOR-05-02 bug-report Round 5 #1 — evaluates a room-dimension VALUE formula such as
 * {@code =25+31+12}. It's a pure-arithmetic subset of the work-volume formula language: numeric
 * constants, {@code + - * /} and parentheses only. Variables, cross-work references and functions
 * are rejected.
 *
 * <p>Reuses {@link FormulaParser} (which strips the optional leading {@code =}) and
 * {@link FormulaEvaluator}. A decimal comma ({@code 2,5}) is accepted and normalized to a dot first.
 * In this constants-only subset a comma can only be a decimal separator, never an argument separator.
 * The result is rounded to the dimension columns' scale (2, HALF_UP).
 *
 * <p>Every failure (empty, unparsable, non-arithmetic node, division by zero) is reported as
 * {@code 400 error.room.dimension.formula.invalid} with the offending dimension field as the
 * argument, so the caller sees which cell is wrong.
 */
public final class DimensionFormulaEvaluator {

    /** The single rejection code for an invalid dimension formula. */
    public static final String FORMULA_INVALID_MESSAGE = "error.room.dimension.formula.invalid";

    private static final int SCALE = 2;

    private DimensionFormulaEvaluator() {
    }

    /**
     * Evaluates {@code formula} for the dimension {@code field}.
     *
     * @param field   the dimension field the formula belongs to (used in the error argument)
     * @param formula the formula text, with or without the leading {@code =}
     * @return the value rounded to 2 decimals
     * @throws ForemenApiException {@code 400 error.room.dimension.formula.invalid}
     */
    public static BigDecimal evaluate(String field, String formula) {
        return evaluate(field, formula, SCALE);
    }

    /**
     * Same as {@link #evaluate(String, String)} but rounds HALF_UP to {@code scale} decimals
     * (used by the geometry path keys: 3 for lengths, 0 for angles).
     */
    public static BigDecimal evaluate(String field, String formula, int scale) {
        if (formula == null || formula.isBlank()) {
            throw invalid(field);
        }
        String normalized = formula.trim().replace(',', '.');
        if (normalized.equals("=") || normalized.isEmpty()) {
            throw invalid(field);
        }
        FormulaAst ast;
        try {
            ast = FormulaParser.parse(normalized);
        } catch (ForemenApiException | IllegalArgumentException e) {
            throw invalid(field);
        }
        if (!isArithmeticOnly(ast)) {
            throw invalid(field);
        }
        try {
            BigDecimal value = FormulaEvaluator.evaluate(ast, Map.of(), ref -> BigDecimal.ZERO);
            return value.setScale(scale, RoundingMode.HALF_UP);
        } catch (ForemenApiException | ArithmeticException e) {
            throw invalid(field); // e.g. division by zero
        }
    }

    /** True iff the tree contains only numeric constants and binary arithmetic operations. */
    static boolean isArithmeticOnly(FormulaAst ast) {
        if (ast instanceof FormulaAst.Const) {
            return true;
        }
        if (ast instanceof FormulaAst.BinOp binOp) {
            return isArithmeticOnly(binOp.l()) && isArithmeticOnly(binOp.r());
        }
        return false; // Var, WorkRef, Cond (IFS), Count (COUNTIF)
    }

    private static ForemenApiException invalid(String field) {
        return new ForemenApiException(HttpStatus.BAD_REQUEST, FORMULA_INVALID_MESSAGE, field);
    }
}
