package com.foremen.service.query.property;

import com.foremen.service.query.QueryOperator;
import com.foremen.service.query.QueryToken;
import com.foremen.service.query.QueryTokenizer;
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 1: Query Parser Structural Correctness (Round-Trip)
 *
 * For any valid query AST (generated from the grammar: arbitrary combinations of filter
 * expressions with AND/OR operators and nested parenthesized groups up to depth 3),
 * serializing the AST to a raw query string and then tokenizing it back via
 * QueryTokenizer.tokenize() SHALL produce a token list that structurally matches
 * the original AST (same number of filters, same operators, same field/value pairs,
 * same logical structure).
 *
 * Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 5.10, 5.12, 5.13, 5.14, 5.15
 */
class QueryParserRoundTripPropertyTest {

    /**
     * Operators that produce non-empty string values in serialization.
     * Excludes NULL/NOT_NULL since they have empty values which complicates the round-trip.
     */
    private static final Set<QueryOperator> OPERATORS_WITH_VALUES = Set.of(
            QueryOperator.EQUALS,
            QueryOperator.NOT_EQUALS,
            QueryOperator.CONTAINS,
            QueryOperator.STARTS_WITH,
            QueryOperator.ENDS_WITH,
            QueryOperator.CONTAINS_CS,
            QueryOperator.STARTS_WITH_CS,
            QueryOperator.ENDS_WITH_CS,
            QueryOperator.LIKE,
            QueryOperator.GREATER_THAN,
            QueryOperator.LESS_THAN,
            QueryOperator.GREATER_THAN_OR_EQUAL,
            QueryOperator.LESS_THAN_OR_EQUAL,
            QueryOperator.GT_DATE,
            QueryOperator.LT_DATE,
            QueryOperator.IN,
            QueryOperator.NOT_IN
    );

    // --- AST representation ---

    sealed interface QueryAst {
        record Filter(String field, QueryOperator operator, String value) implements QueryAst {}
        record And(QueryAst left, QueryAst right) implements QueryAst {}
        record Or(QueryAst left, QueryAst right) implements QueryAst {}
    }

    // --- Serialization: AST → raw query string ---

    private String serialize(QueryAst ast) {
        return switch (ast) {
            case QueryAst.Filter f -> f.field() + f.operator().getSymbol() + f.value();
            case QueryAst.And and -> "(" + serialize(and.left()) + " AND " + serialize(and.right()) + ")";
            case QueryAst.Or or -> "(" + serialize(or.left()) + " OR " + serialize(or.right()) + ")";
        };
    }

    // --- Extract filters from AST in order (left-to-right DFS) ---

    private List<QueryAst.Filter> extractFilters(QueryAst ast) {
        List<QueryAst.Filter> filters = new ArrayList<>();
        collectFilters(ast, filters);
        return filters;
    }

    private void collectFilters(QueryAst ast, List<QueryAst.Filter> accumulator) {
        switch (ast) {
            case QueryAst.Filter f -> accumulator.add(f);
            case QueryAst.And and -> {
                collectFilters(and.left(), accumulator);
                collectFilters(and.right(), accumulator);
            }
            case QueryAst.Or or -> {
                collectFilters(or.left(), accumulator);
                collectFilters(or.right(), accumulator);
            }
        }
    }

    // --- Extract logical structure tokens from AST (And/Or in DFS order) ---

    private List<String> extractLogicalStructure(QueryAst ast) {
        List<String> structure = new ArrayList<>();
        collectLogicalStructure(ast, structure);
        return structure;
    }

    private void collectLogicalStructure(QueryAst ast, List<String> accumulator) {
        switch (ast) {
            case QueryAst.Filter _ -> accumulator.add("FILTER");
            case QueryAst.And and -> {
                accumulator.add("AND_START");
                collectLogicalStructure(and.left(), accumulator);
                collectLogicalStructure(and.right(), accumulator);
                accumulator.add("AND_END");
            }
            case QueryAst.Or or -> {
                accumulator.add("OR_START");
                collectLogicalStructure(or.left(), accumulator);
                collectLogicalStructure(or.right(), accumulator);
                accumulator.add("OR_END");
            }
        }
    }

    // --- Extract filter tokens from tokenizer output ---

    private List<QueryToken.Filter> extractFilterTokens(List<QueryToken> tokens) {
        return tokens.stream()
                .filter(t -> t instanceof QueryToken.Filter)
                .map(t -> (QueryToken.Filter) t)
                .toList();
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<String> alphaFields() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(2)
                .ofMaxLength(8)
                .map(String::toLowerCase)
                .filter(s -> !s.equalsIgnoreCase("and") && !s.equalsIgnoreCase("or"));
    }

    @Provide
    Arbitrary<String> alphanumericValues() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(10);
    }

    @Provide
    Arbitrary<QueryOperator> operators() {
        return Arbitraries.of(OPERATORS_WITH_VALUES.toArray(new QueryOperator[0]));
    }

    @Provide
    Arbitrary<QueryAst> queryAsts() {
        return queryAstArbitrary(0);
    }

    private Arbitrary<QueryAst> queryAstArbitrary(int depth) {
        Arbitrary<QueryAst> leaf = Combinators.combine(
                alphaFields(),
                operators(),
                alphanumericValues()
        ).as(QueryAst.Filter::new);

        if (depth >= 3) {
            return leaf;
        }

        Arbitrary<QueryAst> andNode = Combinators.combine(
                queryAstArbitrary(depth + 1),
                queryAstArbitrary(depth + 1)
        ).as(QueryAst.And::new);

        Arbitrary<QueryAst> orNode = Combinators.combine(
                queryAstArbitrary(depth + 1),
                queryAstArbitrary(depth + 1)
        ).as(QueryAst.Or::new);

        return Arbitraries.frequencyOf(
                Tuple.of(3, leaf),
                Tuple.of(2, andNode),
                Tuple.of(2, orNode)
        );
    }

    // --- Property tests ---

    @Property(tries = 100)
    void roundTripPreservesFilterCount(@ForAll("queryAsts") QueryAst ast) {
        // Serialize AST to string
        String serialized = serialize(ast);

        // Tokenize the string
        List<QueryToken> tokens = QueryTokenizer.tokenize(serialized);

        // Extract filters from both
        List<QueryAst.Filter> originalFilters = extractFilters(ast);
        List<QueryToken.Filter> tokenizedFilters = extractFilterTokens(tokens);

        // Same number of filters
        assertThat(tokenizedFilters).hasSameSizeAs(originalFilters);
    }

    @Property(tries = 100)
    void roundTripPreservesFieldNames(@ForAll("queryAsts") QueryAst ast) {
        String serialized = serialize(ast);
        List<QueryToken> tokens = QueryTokenizer.tokenize(serialized);

        List<QueryAst.Filter> originalFilters = extractFilters(ast);
        List<QueryToken.Filter> tokenizedFilters = extractFilterTokens(tokens);

        // All field names match in order
        for (int i = 0; i < originalFilters.size(); i++) {
            assertThat(tokenizedFilters.get(i).field())
                    .as("Filter %d field should match", i)
                    .isEqualTo(originalFilters.get(i).field());
        }
    }

    @Property(tries = 100)
    void roundTripPreservesOperators(@ForAll("queryAsts") QueryAst ast) {
        String serialized = serialize(ast);
        List<QueryToken> tokens = QueryTokenizer.tokenize(serialized);

        List<QueryAst.Filter> originalFilters = extractFilters(ast);
        List<QueryToken.Filter> tokenizedFilters = extractFilterTokens(tokens);

        // All operators match in order
        for (int i = 0; i < originalFilters.size(); i++) {
            assertThat(tokenizedFilters.get(i).operator())
                    .as("Filter %d operator should match", i)
                    .isEqualTo(originalFilters.get(i).operator());
        }
    }

    @Property(tries = 100)
    void roundTripPreservesValues(@ForAll("queryAsts") QueryAst ast) {
        String serialized = serialize(ast);
        List<QueryToken> tokens = QueryTokenizer.tokenize(serialized);

        List<QueryAst.Filter> originalFilters = extractFilters(ast);
        List<QueryToken.Filter> tokenizedFilters = extractFilterTokens(tokens);

        // All values match in order
        for (int i = 0; i < originalFilters.size(); i++) {
            assertThat(tokenizedFilters.get(i).value())
                    .as("Filter %d value should match", i)
                    .isEqualTo(originalFilters.get(i).value());
        }
    }

    @Property(tries = 100)
    void roundTripPreservesLogicalOperatorCount(@ForAll("queryAsts") QueryAst ast) {
        String serialized = serialize(ast);
        List<QueryToken> tokens = QueryTokenizer.tokenize(serialized);

        // Count AND/OR tokens in the tokenized result
        long andCount = tokens.stream().filter(t -> t instanceof QueryToken.And).count();
        long orCount = tokens.stream().filter(t -> t instanceof QueryToken.Or).count();

        // Count AND/OR nodes in the original AST
        long expectedAndCount = countLogicalNodes(ast, true);
        long expectedOrCount = countLogicalNodes(ast, false);

        assertThat(andCount)
                .as("AND token count should match AST AND node count")
                .isEqualTo(expectedAndCount);
        assertThat(orCount)
                .as("OR token count should match AST OR node count")
                .isEqualTo(expectedOrCount);
    }

    @Property(tries = 100)
    void roundTripPreservesParenthesesBalance(@ForAll("queryAsts") QueryAst ast) {
        String serialized = serialize(ast);
        List<QueryToken> tokens = QueryTokenizer.tokenize(serialized);

        long openParens = tokens.stream().filter(t -> t instanceof QueryToken.OpenParen).count();
        long closeParens = tokens.stream().filter(t -> t instanceof QueryToken.CloseParen).count();

        // Parentheses must be balanced
        assertThat(openParens).isEqualTo(closeParens);

        // Count expected parens: each AND/OR node in AST wraps with parens
        long expectedParens = countLogicalNodes(ast, true) + countLogicalNodes(ast, false);
        assertThat(openParens)
                .as("Each AND/OR node should produce a paren pair")
                .isEqualTo(expectedParens);
    }

    @Property(tries = 100)
    void serializedQueryIsAlwaysParsableByTokenizer(@ForAll("queryAsts") QueryAst ast) {
        String serialized = serialize(ast);

        // Should not throw any exception
        List<QueryToken> tokens = QueryTokenizer.tokenize(serialized);

        // Should produce a non-empty token list
        assertThat(tokens).isNotEmpty();
    }

    // --- Helpers ---

    private long countLogicalNodes(QueryAst ast, boolean countAnd) {
        return switch (ast) {
            case QueryAst.Filter _ -> 0;
            case QueryAst.And and -> (countAnd ? 1 : 0)
                    + countLogicalNodes(and.left(), countAnd)
                    + countLogicalNodes(and.right(), countAnd);
            case QueryAst.Or or -> (!countAnd ? 1 : 0)
                    + countLogicalNodes(or.left(), countAnd)
                    + countLogicalNodes(or.right(), countAnd);
        };
    }
}
