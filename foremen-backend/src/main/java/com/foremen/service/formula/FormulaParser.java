package com.foremen.service.formula;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.dao.model.formula.FormulaAst.BinOp;
import com.foremen.dao.model.formula.FormulaAst.BinOperator;
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
 * Parses a formula source string into a {@link FormulaAst} (FOR-05-04 §Components Component 4,
 * §6.2 {@code parseAndValidate}, task 8.1).
 *
 * <p>Grammar (the {@code Oferta} arithmetic subset):
 * <pre>
 *   expr       := term (('+' | '-') term)*
 *   term       := factor (('*' | '/') factor)*
 *   factor     := '-' factor | primary
 *   primary    := number | workRef | 'PRESENT' '(' workRef ')' | identifier
 *               | 'IFS' '(' ifsArgs ')' | 'COUNTIF' '(' expr ',' cmpOp ',' expr ')'
 *               | '(' expr ')'
 *   ifsArgs    := comparison ',' expr (',' comparison ',' expr)* (',' expr)?
 *   comparison := expr cmpOp expr
 *   cmpOp      := '=' | '<>' | '<' | '<=' | '>' | '>='
 * </pre>
 *
 * <p>A bare {@code WorkRef} token (e.g. {@code X28}) parses to {@link WorkRefMode#VOLUME}; a
 * {@code PRESENT(X28)} wrapper parses to {@link WorkRefMode#PRESENT} (Requirement 3.1's
 * volume/presence distinction — the source grammar has no other explicit marker for it, so
 * this parser introduces the {@code PRESENT(...)} function-call form, consistent with the
 * {@code IFS}/{@code COUNTIF} function-call style already present in the source examples).
 *
 * <p>Any input outside this grammar — an unrecognised character (rejected earlier, by
 * {@link FormulaTokenizer}), an unexpected token, a malformed expression, or an unknown
 * function name — throws {@code 400 error.formula.illegal.operator}. This parser performs
 * <b>no semantic validation</b>: it does not check that a {@code Var} name is one of the 14
 * room dimensions, or that a {@code WorkRef} resolves to a known work — that is
 * {@code FormulaValidator}'s job (task 8.2), which runs on the AST this parser returns.
 *
 * <p>Pure and stateless: same input string always yields an equal AST, and parsing has no
 * side effects.
 */
public final class FormulaParser {

    private FormulaParser() {
    }

    /**
     * Parses {@code source} into a {@link FormulaAst}.
     *
     * @throws ForemenApiException {@code 400 error.formula.illegal.operator} when {@code source}
     *                              is null/blank or falls outside the supported grammar
     */
    public static FormulaAst parse(String source) {
        List<FormulaToken> tokens = FormulaTokenizer.tokenize(source);
        Cursor cursor = new Cursor(tokens);

        FormulaAst ast = parseExpr(cursor);

        if (cursor.hasMore()) {
            throw illegalOperator(cursor.peek());
        }

        return ast;
    }

    // expr := term (('+' | '-') term)*
    private static FormulaAst parseExpr(Cursor cursor) {
        FormulaAst left = parseTerm(cursor);

        while (cursor.hasMore()) {
            FormulaToken token = cursor.peek();
            BinOperator op;
            if (token instanceof FormulaToken.Plus) {
                op = BinOperator.ADD;
            } else if (token instanceof FormulaToken.Minus) {
                op = BinOperator.SUB;
            } else {
                break;
            }
            cursor.consume();
            FormulaAst right = parseTerm(cursor);
            left = new BinOp(op, left, right);
        }

        return left;
    }

    // term := factor (('*' | '/') factor)*
    private static FormulaAst parseTerm(Cursor cursor) {
        FormulaAst left = parseFactor(cursor);

        while (cursor.hasMore()) {
            FormulaToken token = cursor.peek();
            BinOperator op;
            if (token instanceof FormulaToken.Star) {
                op = BinOperator.MUL;
            } else if (token instanceof FormulaToken.Slash) {
                op = BinOperator.DIV;
            } else {
                break;
            }
            cursor.consume();
            FormulaAst right = parseFactor(cursor);
            left = new BinOp(op, left, right);
        }

        return left;
    }

    // factor := '-' factor | primary
    private static FormulaAst parseFactor(Cursor cursor) {
        if (cursor.hasMore() && cursor.peek() instanceof FormulaToken.Minus) {
            cursor.consume();
            FormulaAst operand = parseFactor(cursor);
            return new BinOp(BinOperator.SUB, new Const(java.math.BigDecimal.ZERO), operand);
        }
        return parsePrimary(cursor);
    }

    // primary := number | workRef | 'PRESENT' '(' workRef ')' | identifier
    //          | 'IFS' '(' ifsArgs ')' | 'COUNTIF' '(' expr ',' cmpOp ',' expr ')' | '(' expr ')'
    private static FormulaAst parsePrimary(Cursor cursor) {
        if (!cursor.hasMore()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.formula.illegal.operator", "<end>");
        }

        FormulaToken token = cursor.consume();

        if (token instanceof FormulaToken.NumberToken number) {
            return new Const(number.value());
        }

        if (token instanceof FormulaToken.WorkRefToken workRef) {
            return new WorkRef(workRef.ref(), WorkRefMode.VOLUME);
        }

        if (token instanceof FormulaToken.OpenParen) {
            FormulaAst inner = parseExpr(cursor);
            expect(cursor, FormulaToken.CloseParen.class);
            return inner;
        }

        if (token instanceof FormulaToken.Ident ident) {
            String name = ident.name();
            if ("IFS".equals(name)) {
                return parseIfs(cursor);
            }
            if ("COUNTIF".equals(name)) {
                return parseCountif(cursor);
            }
            if ("PRESENT".equals(name)) {
                return parsePresent(cursor);
            }
            return new Var(name);
        }

        throw illegalOperator(token);
    }

    // 'PRESENT' '(' workRef ')'
    private static FormulaAst parsePresent(Cursor cursor) {
        expect(cursor, FormulaToken.OpenParen.class);
        if (!cursor.hasMore() || !(cursor.peek() instanceof FormulaToken.WorkRefToken)) {
            throw illegalOperator(cursor.hasMore() ? cursor.peek() : null);
        }
        FormulaToken.WorkRefToken workRefToken = (FormulaToken.WorkRefToken) cursor.consume();
        expect(cursor, FormulaToken.CloseParen.class);
        return new WorkRef(workRefToken.ref(), WorkRefMode.PRESENT);
    }

    // 'IFS' '(' comparison ',' expr (',' comparison ',' expr)* (',' expr)? ')'
    private static FormulaAst parseIfs(Cursor cursor) {
        expect(cursor, FormulaToken.OpenParen.class);

        List<CondCase> cases = new ArrayList<>();
        FormulaAst elseExpr = null;

        // First pair is mandatory: comparison ',' expr
        Comparison firstWhen = parseComparison(cursor);
        expect(cursor, FormulaToken.Comma.class);
        FormulaAst firstThen = parseExpr(cursor);
        cases.add(new CondCase(firstWhen, firstThen));

        while (cursor.hasMore() && cursor.peek() instanceof FormulaToken.Comma) {
            cursor.consume(); // comma

            int checkpoint = cursor.position();
            Comparison when;
            try {
                when = parseComparison(cursor);
            } catch (ForemenApiException e) {
                // Not a comparison — must be the trailing else-expression, and must be the last arg.
                cursor.reset(checkpoint);
                elseExpr = parseExpr(cursor);
                break;
            }

            if (!cursor.hasMore() || !(cursor.peek() instanceof FormulaToken.Comma)) {
                // A comparison with no following ',' expr is illegal — IFS cases come in pairs.
                throw illegalOperator(cursor.hasMore() ? cursor.peek() : null);
            }
            cursor.consume(); // comma
            FormulaAst then = parseExpr(cursor);
            cases.add(new CondCase(when, then));
        }

        expect(cursor, FormulaToken.CloseParen.class);
        return new Cond(cases, elseExpr);
    }

    // 'COUNTIF' '(' expr ',' cmpOp ',' expr ')'
    private static FormulaAst parseCountif(Cursor cursor) {
        expect(cursor, FormulaToken.OpenParen.class);
        FormulaAst subject = parseExpr(cursor);
        expect(cursor, FormulaToken.Comma.class);
        CompareOperator cmp = parseCompareOperator(cursor);
        expect(cursor, FormulaToken.Comma.class);
        FormulaAst value = parseExpr(cursor);
        expect(cursor, FormulaToken.CloseParen.class);
        return new Count(subject, cmp, value);
    }

    // comparison := expr cmpOp expr
    private static Comparison parseComparison(Cursor cursor) {
        FormulaAst left = parseExpr(cursor);
        CompareOperator cmp = parseCompareOperator(cursor);
        FormulaAst right = parseExpr(cursor);
        return new Comparison(left, cmp, right);
    }

    private static CompareOperator parseCompareOperator(Cursor cursor) {
        if (!cursor.hasMore()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.formula.illegal.operator", "<end>");
        }
        FormulaToken token = cursor.consume();
        if (token instanceof FormulaToken.Eq) {
            return CompareOperator.EQ;
        }
        if (token instanceof FormulaToken.Neq) {
            return CompareOperator.NEQ;
        }
        if (token instanceof FormulaToken.Lt) {
            return CompareOperator.LT;
        }
        if (token instanceof FormulaToken.Lte) {
            return CompareOperator.LTE;
        }
        if (token instanceof FormulaToken.Gt) {
            return CompareOperator.GT;
        }
        if (token instanceof FormulaToken.Gte) {
            return CompareOperator.GTE;
        }
        throw illegalOperator(token);
    }

    private static void expect(Cursor cursor, Class<? extends FormulaToken> expected) {
        if (!cursor.hasMore()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.formula.illegal.operator", "<end>");
        }
        FormulaToken token = cursor.consume();
        if (!expected.isInstance(token)) {
            throw illegalOperator(token);
        }
    }

    private static ForemenApiException illegalOperator(FormulaToken token) {
        String description = token == null ? "<end>" : token.toString();
        return new ForemenApiException(HttpStatus.BAD_REQUEST, "error.formula.illegal.operator", description);
    }

    /** A mutable cursor over the token list, supporting backtracking (needed for IFS's trailing else). */
    private static final class Cursor {
        private final List<FormulaToken> tokens;
        private int position = 0;

        Cursor(List<FormulaToken> tokens) {
            this.tokens = tokens;
        }

        boolean hasMore() {
            return position < tokens.size();
        }

        FormulaToken peek() {
            return tokens.get(position);
        }

        FormulaToken consume() {
            return tokens.get(position++);
        }

        int position() {
            return position;
        }

        void reset(int checkpoint) {
            this.position = checkpoint;
        }
    }
}
