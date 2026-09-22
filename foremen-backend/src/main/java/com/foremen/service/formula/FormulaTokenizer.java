package com.foremen.service.formula;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.foremen.exception.ForemenApiException;

/**
 * Lexes a formula source string (the {@code Oferta} arithmetic subset — see design.md §6.2)
 * into a flat token list consumed by {@link FormulaParser}.
 *
 * <p>Recognises: numeric constants (integer/decimal), a leading {@code =} (optional, matching
 * the spreadsheet-formula convention and stripped before tokenizing), cell-style {@code WorkRef}
 * tokens (uppercase column letters immediately followed by digits, e.g. {@code X28},
 * {@code AL48}), bare identifiers ({@code Var} names and function keywords {@code IFS},
 * {@code COUNTIF}, {@code PRESENT}), the four arithmetic operators, parentheses, comma, and the
 * six comparison operators used inside {@code IFS}/{@code COUNTIF} conditions.
 *
 * <p>Any character outside this grammar throws {@code 400 error.formula.illegal.operator} (the
 * single catch-all rejection code for out-of-grammar input, per task 8.1 / design.md §6.2).
 */
final class FormulaTokenizer {

    /** {@code WorkRef} cell notation: one or more uppercase letters immediately followed by one or more digits. */
    private static final Pattern WORK_REF_PATTERN = Pattern.compile("^[A-Z]+[0-9]+");

    /** A bare identifier: starts with a letter, continues with letters/digits (camelCase variable names, keywords). */
    private static final Pattern IDENT_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9]*");

    private static final Pattern NUMBER_PATTERN = Pattern.compile("^[0-9]+(\\.[0-9]+)?");

    private FormulaTokenizer() {
    }

    static List<FormulaToken> tokenize(String source) {
        if (source == null || source.isBlank()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.formula.illegal.operator", "");
        }

        String input = source.trim();
        if (input.startsWith("=")) {
            input = input.substring(1);
        }

        List<FormulaToken> tokens = new ArrayList<>();
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            switch (c) {
                case '+' -> {
                    tokens.add(new FormulaToken.Plus());
                    i++;
                    continue;
                }
                case '-' -> {
                    tokens.add(new FormulaToken.Minus());
                    i++;
                    continue;
                }
                case '*' -> {
                    tokens.add(new FormulaToken.Star());
                    i++;
                    continue;
                }
                case '/' -> {
                    tokens.add(new FormulaToken.Slash());
                    i++;
                    continue;
                }
                case '(' -> {
                    tokens.add(new FormulaToken.OpenParen());
                    i++;
                    continue;
                }
                case ')' -> {
                    tokens.add(new FormulaToken.CloseParen());
                    i++;
                    continue;
                }
                case ',' -> {
                    tokens.add(new FormulaToken.Comma());
                    i++;
                    continue;
                }
                case '=' -> {
                    tokens.add(new FormulaToken.Eq());
                    i++;
                    continue;
                }
                case '<' -> {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '=') {
                        tokens.add(new FormulaToken.Lte());
                        i += 2;
                    } else if (i + 1 < input.length() && input.charAt(i + 1) == '>') {
                        tokens.add(new FormulaToken.Neq());
                        i += 2;
                    } else {
                        tokens.add(new FormulaToken.Lt());
                        i++;
                    }
                    continue;
                }
                case '>' -> {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '=') {
                        tokens.add(new FormulaToken.Gte());
                        i += 2;
                    } else {
                        tokens.add(new FormulaToken.Gt());
                        i++;
                    }
                    continue;
                }
                default -> {
                    // fall through to pattern-based matching below
                }
            }

            String remaining = input.substring(i);

            Matcher numberMatcher = NUMBER_PATTERN.matcher(remaining);
            if (numberMatcher.lookingAt()) {
                String match = numberMatcher.group();
                tokens.add(new FormulaToken.NumberToken(new BigDecimal(match)));
                i += match.length();
                continue;
            }

            Matcher workRefMatcher = WORK_REF_PATTERN.matcher(remaining);
            if (workRefMatcher.lookingAt()) {
                String match = workRefMatcher.group();
                tokens.add(new FormulaToken.WorkRefToken(match));
                i += match.length();
                continue;
            }

            Matcher identMatcher = IDENT_PATTERN.matcher(remaining);
            if (identMatcher.lookingAt()) {
                String match = identMatcher.group();
                tokens.add(new FormulaToken.Ident(match));
                i += match.length();
                continue;
            }

            throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                    "error.formula.illegal.operator", String.valueOf(c));
        }

        return tokens;
    }
}
