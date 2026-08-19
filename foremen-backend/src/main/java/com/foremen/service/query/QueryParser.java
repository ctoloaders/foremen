package com.foremen.service.query;

import com.foremen.exception.ForemenApiException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Set;

public class QueryParser {

    public static <T> Specification<T> parse(String rawQuery, Class<T> entityClass,
                                              Set<String> i18nProperties, String localeSuffix) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return (root, query, cb) -> null;
        }

        List<QueryToken> tokens = QueryTokenizer.tokenize(rawQuery);

        // Resolve i18n fields in filter tokens BEFORE building specifications
        List<QueryToken> resolvedTokens = resolveI18nFilterFields(tokens, i18nProperties, localeSuffix);

        TokenStream stream = new TokenStream(resolvedTokens);
        Specification<T> result = parseQuery(stream, entityClass);

        if (stream.hasMore()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                    "error.query.parse.unexpected.token", stream.peek().toString());
        }

        return result;
    }

    private static List<QueryToken> resolveI18nFilterFields(List<QueryToken> tokens,
                                                             Set<String> i18nProperties,
                                                             String localeSuffix) {
        if (i18nProperties == null || i18nProperties.isEmpty()) {
            return tokens;
        }

        return tokens.stream().map(token -> {
            if (token instanceof QueryToken.Filter filter) {
                String resolvedField = resolveFieldName(filter.field(), i18nProperties, localeSuffix);
                return new QueryToken.Filter(resolvedField, filter.operator(), filter.value());
            }
            return token;
        }).toList();
    }

    private static String resolveFieldName(String field, Set<String> i18nProperties, String localeSuffix) {
        if (field.contains(".")) {
            int lastDot = field.lastIndexOf('.');
            String prefix = field.substring(0, lastDot);
            String finalSegment = field.substring(lastDot + 1);
            if (i18nProperties.contains(finalSegment)) {
                return prefix + "." + finalSegment + localeSuffix;
            }
            return field;
        }

        if (i18nProperties.contains(field)) {
            return field + localeSuffix;
        }
        return field;
    }

    private static <T> Specification<T> parseQuery(TokenStream stream, Class<T> entityClass) {
        Specification<T> left = parseExpression(stream, entityClass);

        while (stream.hasMore()) {
            QueryToken token = stream.peek();
            switch (token) {
                case QueryToken.And ignored -> {
                    stream.consume();
                    Specification<T> right = parseExpression(stream, entityClass);
                    left = Specification.where(left).and(right);
                }
                case QueryToken.Or ignored -> {
                    stream.consume();
                    Specification<T> right = parseExpression(stream, entityClass);
                    left = Specification.where(left).or(right);
                }
                case QueryToken.CloseParen ignored -> {
                    // End of parenthesized group — return to caller
                    return left;
                }
                default -> throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                        "error.query.parse.unexpected.token", token.toString());
            }
        }

        return left;
    }

    private static <T> Specification<T> parseExpression(TokenStream stream, Class<T> entityClass) {
        if (!stream.hasMore()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                    "error.query.parse.unexpected.end");
        }

        QueryToken token = stream.peek();
        if (token instanceof QueryToken.OpenParen) {
            stream.consume(); // consume '('
            Specification<T> inner = parseQuery(stream, entityClass);
            expectToken(stream, QueryToken.CloseParen.class);
            return inner;
        } else if (token instanceof QueryToken.Filter filter) {
            stream.consume();
            return SpecificationBuilder.buildPredicate(filter, entityClass);
        } else {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                    "error.query.parse.expected.expression", token.toString());
        }
    }

    private static void expectToken(TokenStream stream, Class<? extends QueryToken> expected) {
        if (!stream.hasMore()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                    "error.query.parse.unexpected.end");
        }
        QueryToken token = stream.consume();
        if (!expected.isInstance(token)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                    "error.query.parse.expected.token", expected.getSimpleName(), token.toString());
        }
    }
}
