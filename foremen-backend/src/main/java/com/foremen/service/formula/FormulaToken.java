package com.foremen.service.formula;

import java.math.BigDecimal;

/**
 * Lexical tokens produced by {@link FormulaTokenizer} and consumed by {@link FormulaParser}.
 *
 * <p>Pure data carriers — no behaviour. {@code Ident} covers both {@code Var} names (bare
 * lower/camel-case identifiers, e.g. {@code floorArea}) and function-style keywords
 * ({@code IFS}, {@code COUNTIF}, {@code PRESENT}) since both are lexically "letters"; the
 * parser disambiguates by what follows (an open paren for a function call) and by whether the
 * token also matches the {@code WorkRef} cell-notation shape (checked separately by
 * {@link FormulaTokenizer} and emitted as {@link WorkRefToken} instead).
 */
sealed interface FormulaToken {

    record NumberToken(BigDecimal value) implements FormulaToken {
    }

    /** A bare identifier: a {@code Var} name, or a function keyword ({@code IFS}, {@code COUNTIF}, {@code PRESENT}). */
    record Ident(String name) implements FormulaToken {
    }

    /** A cell-style cross-work reference, e.g. {@code X28}, {@code AL48}: uppercase column letters + digits. */
    record WorkRefToken(String ref) implements FormulaToken {
    }

    record Plus() implements FormulaToken {
    }

    record Minus() implements FormulaToken {
    }

    record Star() implements FormulaToken {
    }

    record Slash() implements FormulaToken {
    }

    record OpenParen() implements FormulaToken {
    }

    record CloseParen() implements FormulaToken {
    }

    record Comma() implements FormulaToken {
    }

    record Eq() implements FormulaToken {
    }

    record Neq() implements FormulaToken {
    }

    record Lt() implements FormulaToken {
    }

    record Lte() implements FormulaToken {
    }

    record Gt() implements FormulaToken {
    }

    record Gte() implements FormulaToken {
    }
}
