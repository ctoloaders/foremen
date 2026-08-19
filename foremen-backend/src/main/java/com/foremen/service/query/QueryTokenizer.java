package com.foremen.service.query;

import com.foremen.exception.ForemenApiException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryTokenizer {

    private static final Pattern AND_PATTERN = Pattern.compile("\\bAND\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OR_PATTERN = Pattern.compile("\\bOR\\b", Pattern.CASE_INSENSITIVE);

    public static List<QueryToken> tokenize(String rawQuery) {
        List<QueryToken> tokens = new ArrayList<>();
        int i = 0;
        String input = rawQuery.trim();

        while (i < input.length()) {
            char c = input.charAt(i);

            // Skip whitespace
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            // Semicolons are NOT valid
            if (c == ';') {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                        "error.query.tokenize.unexpected.char", String.valueOf(c), i);
            }

            // Parentheses
            if (c == '(') {
                tokens.add(new QueryToken.OpenParen());
                i++;
                continue;
            }
            if (c == ')') {
                tokens.add(new QueryToken.CloseParen());
                i++;
                continue;
            }

            // AND/OR keywords (must use word boundaries to avoid matching "ANDROID", "ORDINARY")
            String remaining = input.substring(i);
            Matcher andMatcher = AND_PATTERN.matcher(remaining);
            if (andMatcher.lookingAt()) {
                tokens.add(new QueryToken.And());
                i += 3;
                continue;
            }
            Matcher orMatcher = OR_PATTERN.matcher(remaining);
            if (orMatcher.lookingAt()) {
                tokens.add(new QueryToken.Or());
                i += 2;
                continue;
            }

            // Filter expression: field OPERATOR value
            QueryToken.Filter filter = tryParseFilter(input, i);
            if (filter != null) {
                tokens.add(filter);
                i += filterLength(input, i, filter);
                continue;
            }

            throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                    "error.query.tokenize.unexpected.char", String.valueOf(c), i);
        }

        return tokens;
    }

    private static QueryToken.Filter tryParseFilter(String input, int start) {
        // Find the operator closest to start position. Use ORDERED_FOR_MATCHING (longest-first)
        // to resolve ties — when two operators start at the same position, the longer one wins.
        int bestOpIndex = Integer.MAX_VALUE;
        QueryOperator bestOp = null;

        int delimiterPos = nextDelimiterPosition(input, start);

        for (QueryOperator op : QueryOperator.ORDERED_FOR_MATCHING) {
            String opSymbol = op.getSymbol();
            int opIndex = input.indexOf(opSymbol, start);
            if (opIndex > start && opIndex < delimiterPos) {
                if (opIndex < bestOpIndex) {
                    bestOpIndex = opIndex;
                    bestOp = op;
                }
                // If same position, the first one found in ORDERED_FOR_MATCHING wins (longest)
            }
        }

        if (bestOp != null) {
            String field = input.substring(start, bestOpIndex).trim();
            int valueStart = bestOpIndex + bestOp.getSymbol().length();
            int valueEnd = findValueEnd(input, valueStart);
            String value = input.substring(valueStart, valueEnd).trim();
            return new QueryToken.Filter(field, bestOp, value);
        }
        return null;
    }

    private static int nextDelimiterPosition(String input, int start) {
        // Find next closing paren — operators must appear before the next delimiter
        int min = input.length();
        int paren = input.indexOf(')', start);
        if (paren > 0) min = Math.min(min, paren);
        return min;
    }

    private static int findValueEnd(String input, int start) {
        // Value ends at closing paren, opening paren, semicolon, or whitespace followed by AND/OR keyword
        for (int i = start; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == ')' || c == '(' || c == ';') return i;
            if (c == ' ') {
                String remaining = input.substring(i).trim();
                Matcher andMatcher = AND_PATTERN.matcher(remaining);
                Matcher orMatcher = OR_PATTERN.matcher(remaining);
                if (andMatcher.lookingAt() || orMatcher.lookingAt()) {
                    return i;
                }
            }
        }
        return input.length();
    }

    private static int filterLength(String input, int start, QueryToken.Filter filter) {
        // Calculate how many chars the filter consumed from the input
        String filterStr = filter.field() + filter.operator().getSymbol() + filter.value();
        return filterStr.length();
    }
}
