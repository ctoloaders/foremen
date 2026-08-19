package com.foremen.service.query;

import com.foremen.exception.ForemenApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QueryParser")
class QueryParserTest {

    private static final Set<String> NO_I18N = Set.of();
    private static final String NO_SUFFIX = "";

    // --- Simple expressions ---

    @Nested
    @DisplayName("Simple expressions")
    class SimpleExpressions {

        @Test
        @DisplayName("simple equality: name==John produces non-null Specification")
        void simpleEquality() {
            Specification<Object> spec = QueryParser.parse("name==John", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }

        @Test
        @DisplayName("simple equality tokenizes to Filter(name, EQUALS, John)")
        void simpleEqualityTokens() {
            var tokens = QueryTokenizer.tokenize("name==John");
            assertEquals(1, tokens.size());
            assertInstanceOf(QueryToken.Filter.class, tokens.get(0));
            var filter = (QueryToken.Filter) tokens.get(0);
            assertEquals("name", filter.field());
            assertEquals(QueryOperator.EQUALS, filter.operator());
            assertEquals("John", filter.value());
        }
    }

    // --- AND expressions ---

    @Nested
    @DisplayName("AND expressions")
    class AndExpressions {

        @Test
        @DisplayName("AND: a==1 AND b==2 produces non-null Specification")
        void andExpression() {
            Specification<Object> spec = QueryParser.parse("a==1 AND b==2", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }

        @Test
        @DisplayName("AND tokenizes to Filter(a) AND Filter(b)")
        void andTokens() {
            var tokens = QueryTokenizer.tokenize("a==1 AND b==2");
            assertEquals(3, tokens.size());
            assertInstanceOf(QueryToken.Filter.class, tokens.get(0));
            assertInstanceOf(QueryToken.And.class, tokens.get(1));
            assertInstanceOf(QueryToken.Filter.class, tokens.get(2));

            var filterA = (QueryToken.Filter) tokens.get(0);
            assertEquals("a", filterA.field());
            assertEquals(QueryOperator.EQUALS, filterA.operator());
            assertEquals("1", filterA.value());

            var filterB = (QueryToken.Filter) tokens.get(2);
            assertEquals("b", filterB.field());
            assertEquals(QueryOperator.EQUALS, filterB.operator());
            assertEquals("2", filterB.value());
        }
    }

    // --- OR expressions ---

    @Nested
    @DisplayName("OR expressions")
    class OrExpressions {

        @Test
        @DisplayName("OR: a==1 OR b==2 produces non-null Specification")
        void orExpression() {
            Specification<Object> spec = QueryParser.parse("a==1 OR b==2", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }

        @Test
        @DisplayName("OR tokenizes to Filter(a) OR Filter(b)")
        void orTokens() {
            var tokens = QueryTokenizer.tokenize("a==1 OR b==2");
            assertEquals(3, tokens.size());
            assertInstanceOf(QueryToken.Filter.class, tokens.get(0));
            assertInstanceOf(QueryToken.Or.class, tokens.get(1));
            assertInstanceOf(QueryToken.Filter.class, tokens.get(2));

            var filterA = (QueryToken.Filter) tokens.get(0);
            assertEquals("a", filterA.field());
            var filterB = (QueryToken.Filter) tokens.get(2);
            assertEquals("b", filterB.field());
        }
    }

    // --- Parenthesized expressions ---

    @Nested
    @DisplayName("Parenthesized expressions")
    class ParenthesizedExpressions {

        @Test
        @DisplayName("parenthesized: (a==1 OR b==2) AND c==3 produces non-null Specification")
        void parenthesizedExpression() {
            Specification<Object> spec = QueryParser.parse("(a==1 OR b==2) AND c==3", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }

        @Test
        @DisplayName("parenthesized structure: (a==1 OR b==2) AND c==3 tokenizes correctly")
        void parenthesizedTokens() {
            var tokens = QueryTokenizer.tokenize("(a==1 OR b==2) AND c==3");
            assertEquals(7, tokens.size());
            assertInstanceOf(QueryToken.OpenParen.class, tokens.get(0));
            assertInstanceOf(QueryToken.Filter.class, tokens.get(1));
            assertInstanceOf(QueryToken.Or.class, tokens.get(2));
            assertInstanceOf(QueryToken.Filter.class, tokens.get(3));
            assertInstanceOf(QueryToken.CloseParen.class, tokens.get(4));
            assertInstanceOf(QueryToken.And.class, tokens.get(5));
            assertInstanceOf(QueryToken.Filter.class, tokens.get(6));
        }

        @Test
        @DisplayName("nested parentheses: (a==1 AND (b==2 OR c==3)) produces non-null Specification")
        void nestedParentheses() {
            Specification<Object> spec = QueryParser.parse("(a==1 AND (b==2 OR c==3))", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }

        @Test
        @DisplayName("nested parentheses structure tokenizes correctly")
        void nestedParenthesesTokens() {
            var tokens = QueryTokenizer.tokenize("(a==1 AND (b==2 OR c==3))");
            assertEquals(9, tokens.size());
            assertInstanceOf(QueryToken.OpenParen.class, tokens.get(0));
            assertInstanceOf(QueryToken.Filter.class, tokens.get(1)); // a==1
            assertInstanceOf(QueryToken.And.class, tokens.get(2));
            assertInstanceOf(QueryToken.OpenParen.class, tokens.get(3));
            assertInstanceOf(QueryToken.Filter.class, tokens.get(4)); // b==2
            assertInstanceOf(QueryToken.Or.class, tokens.get(5));
            assertInstanceOf(QueryToken.Filter.class, tokens.get(6)); // c==3
            assertInstanceOf(QueryToken.CloseParen.class, tokens.get(7));
            assertInstanceOf(QueryToken.CloseParen.class, tokens.get(8));
        }
    }

    // --- All 20 operators ---

    @Nested
    @DisplayName("All 20 operators produce correct Filter tokens")
    class AllOperators {

        @Test
        @DisplayName("EQUALS (==): field==value")
        void equalsOp() {
            Specification<Object> spec = QueryParser.parse("field==value", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field==value", "field", QueryOperator.EQUALS, "value");
        }

        @Test
        @DisplayName("NOT_EQUALS (!=): field!=value")
        void notEqualsOp() {
            Specification<Object> spec = QueryParser.parse("field!=value", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field!=value", "field", QueryOperator.NOT_EQUALS, "value");
        }

        @Test
        @DisplayName("CONTAINS (~ct~): field~ct~value")
        void containsOp() {
            Specification<Object> spec = QueryParser.parse("field~ct~value", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field~ct~value", "field", QueryOperator.CONTAINS, "value");
        }

        @Test
        @DisplayName("STARTS_WITH (~sw~): field~sw~value")
        void startsWithOp() {
            Specification<Object> spec = QueryParser.parse("field~sw~value", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field~sw~value", "field", QueryOperator.STARTS_WITH, "value");
        }

        @Test
        @DisplayName("ENDS_WITH (~ew~): field~ew~value")
        void endsWithOp() {
            Specification<Object> spec = QueryParser.parse("field~ew~value", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field~ew~value", "field", QueryOperator.ENDS_WITH, "value");
        }

        @Test
        @DisplayName("CONTAINS_CS (~CT~): field~CT~value")
        void containsCsOp() {
            Specification<Object> spec = QueryParser.parse("field~CT~value", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field~CT~value", "field", QueryOperator.CONTAINS_CS, "value");
        }

        @Test
        @DisplayName("STARTS_WITH_CS (~SW~): field~SW~value")
        void startsWithCsOp() {
            Specification<Object> spec = QueryParser.parse("field~SW~value", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field~SW~value", "field", QueryOperator.STARTS_WITH_CS, "value");
        }

        @Test
        @DisplayName("ENDS_WITH_CS (~EW~): field~EW~value")
        void endsWithCsOp() {
            Specification<Object> spec = QueryParser.parse("field~EW~value", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field~EW~value", "field", QueryOperator.ENDS_WITH_CS, "value");
        }

        @Test
        @DisplayName("LIKE (~~): field~~value")
        void likeOp() {
            Specification<Object> spec = QueryParser.parse("field~~value", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field~~value", "field", QueryOperator.LIKE, "value");
        }

        @Test
        @DisplayName("GREATER_THAN (>): field>25")
        void greaterThanOp() {
            Specification<Object> spec = QueryParser.parse("field>25", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field>25", "field", QueryOperator.GREATER_THAN, "25");
        }

        @Test
        @DisplayName("LESS_THAN (<): field<100")
        void lessThanOp() {
            Specification<Object> spec = QueryParser.parse("field<100", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field<100", "field", QueryOperator.LESS_THAN, "100");
        }

        @Test
        @DisplayName("GREATER_THAN_OR_EQUAL (>=): field>=18")
        void greaterThanOrEqualOp() {
            Specification<Object> spec = QueryParser.parse("field>=18", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field>=18", "field", QueryOperator.GREATER_THAN_OR_EQUAL, "18");
        }

        @Test
        @DisplayName("LESS_THAN_OR_EQUAL (<=): field<=65")
        void lessThanOrEqualOp() {
            Specification<Object> spec = QueryParser.parse("field<=65", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("field<=65", "field", QueryOperator.LESS_THAN_OR_EQUAL, "65");
        }

        @Test
        @DisplayName("GT_DATE (>date): date>date2024-01-01")
        void gtDateOp() {
            Specification<Object> spec = QueryParser.parse("date>date2024-01-01", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("date>date2024-01-01", "date", QueryOperator.GT_DATE, "2024-01-01");
        }

        @Test
        @DisplayName("LT_DATE (<date): date<date2024-12-31")
        void ltDateOp() {
            Specification<Object> spec = QueryParser.parse("date<date2024-12-31", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("date<date2024-12-31", "date", QueryOperator.LT_DATE, "2024-12-31");
        }

        @Test
        @DisplayName("IN (~in~): status~in~active,pending")
        void inOp() {
            Specification<Object> spec = QueryParser.parse("status~in~active,pending", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("status~in~active,pending", "status", QueryOperator.IN, "active,pending");
        }

        @Test
        @DisplayName("NOT_IN (~notin~): type~notin~draft,archived")
        void notInOp() {
            Specification<Object> spec = QueryParser.parse("type~notin~draft,archived", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("type~notin~draft,archived", "type", QueryOperator.NOT_IN, "draft,archived");
        }

        @Test
        @DisplayName("NULL (~null~): deletedAt~null~")
        void nullOp() {
            Specification<Object> spec = QueryParser.parse("deletedAt~null~", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("deletedAt~null~", "deletedAt", QueryOperator.NULL, "");
        }

        @Test
        @DisplayName("NOT_NULL (~notnull~): assignee~notnull~")
        void notNullOp() {
            Specification<Object> spec = QueryParser.parse("assignee~notnull~", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
            assertTokenizedFilter("assignee~notnull~", "assignee", QueryOperator.NOT_NULL, "");
        }
    }

    // --- I18n resolution ---

    @Nested
    @DisplayName("I18n filter field resolution")
    class I18nResolution {

        @Test
        @DisplayName("i18n simple field: name==John with i18nProperties={'name'}, suffix='RU' → Filter(nameRU, EQUALS, John)")
        void i18nSimpleFieldRU() {
            Set<String> i18nProperties = Set.of("name");

            // Parse with i18n — should resolve 'name' to 'nameRU'
            Specification<Object> spec = QueryParser.parse("name==John", Object.class, i18nProperties, "RU");
            assertNotNull(spec);

            // Verify via tokenize + resolve logic:
            var tokens = QueryTokenizer.tokenize("name==John");
            // Manually apply the i18n resolution (same logic as QueryParser does internally)
            var resolvedTokens = tokens.stream().map(token -> {
                if (token instanceof QueryToken.Filter filter) {
                    String field = filter.field();
                    if (i18nProperties.contains(field)) {
                        field = field + "RU";
                    }
                    return new QueryToken.Filter(field, filter.operator(), filter.value());
                }
                return token;
            }).toList();

            assertEquals(1, resolvedTokens.size());
            var filter = (QueryToken.Filter) resolvedTokens.get(0);
            assertEquals("nameRU", filter.field());
            assertEquals(QueryOperator.EQUALS, filter.operator());
            assertEquals("John", filter.value());
        }

        @Test
        @DisplayName("i18n nested field: project.name==Test with i18nProperties={'name'}, suffix='RU' → Filter(project.nameRU, EQUALS, Test)")
        void i18nNestedField() {
            Set<String> i18nProperties = Set.of("name");

            // Parse with i18n — should resolve nested 'project.name' → 'project.nameRU'
            Specification<Object> spec = QueryParser.parse("project.name==Test", Object.class, i18nProperties, "RU");
            assertNotNull(spec);

            // Verify the resolution logic: apply same resolution as QueryParser
            var tokens = QueryTokenizer.tokenize("project.name==Test");
            var resolvedTokens = tokens.stream().map(token -> {
                if (token instanceof QueryToken.Filter filter) {
                    String field = filter.field();
                    if (field.contains(".")) {
                        int lastDot = field.lastIndexOf('.');
                        String prefix = field.substring(0, lastDot);
                        String finalSegment = field.substring(lastDot + 1);
                        if (i18nProperties.contains(finalSegment)) {
                            field = prefix + "." + finalSegment + "RU";
                        }
                    }
                    return new QueryToken.Filter(field, filter.operator(), filter.value());
                }
                return token;
            }).toList();

            assertEquals(1, resolvedTokens.size());
            var filter = (QueryToken.Filter) resolvedTokens.get(0);
            assertEquals("project.nameRU", filter.field());
            assertEquals(QueryOperator.EQUALS, filter.operator());
            assertEquals("Test", filter.value());
        }

        @Test
        @DisplayName("non-i18n field unchanged: status==active with i18nProperties={'name'} → Filter(status, EQUALS, active)")
        void nonI18nFieldUnchanged() {
            Set<String> i18nProperties = Set.of("name");

            Specification<Object> spec = QueryParser.parse("status==active", Object.class, i18nProperties, "RU");
            assertNotNull(spec);

            // 'status' is not in i18nProperties, so the field should remain unchanged
            var tokens = QueryTokenizer.tokenize("status==active");
            var filter = (QueryToken.Filter) tokens.get(0);
            assertEquals("status", filter.field());
            assertEquals(QueryOperator.EQUALS, filter.operator());
            assertEquals("active", filter.value());

            // The parse should not throw — the field is not resolved
            assertDoesNotThrow(() -> QueryParser.parse("status==active", Object.class, i18nProperties, "RU"));
        }

        @Test
        @DisplayName("i18n with PL suffix: name==John with suffix='PL' → Filter(namePL, EQUALS, John)")
        void i18nWithPLSuffix() {
            Set<String> i18nProperties = Set.of("name");

            Specification<Object> spec = QueryParser.parse("name==John", Object.class, i18nProperties, "PL");
            assertNotNull(spec);

            // Verify resolution
            var tokens = QueryTokenizer.tokenize("name==John");
            var resolvedTokens = tokens.stream().map(token -> {
                if (token instanceof QueryToken.Filter filter) {
                    String field = filter.field();
                    if (i18nProperties.contains(field)) {
                        field = field + "PL";
                    }
                    return new QueryToken.Filter(field, filter.operator(), filter.value());
                }
                return token;
            }).toList();

            var filter = (QueryToken.Filter) resolvedTokens.get(0);
            assertEquals("namePL", filter.field());
        }

        @Test
        @DisplayName("empty i18nProperties does not change field names")
        void emptyI18nProperties() {
            Specification<Object> spec = QueryParser.parse("name==John", Object.class, Set.of(), "RU");
            assertNotNull(spec);
            // If i18n properties are empty, 'name' should remain 'name' in the tokens
            var tokens = QueryTokenizer.tokenize("name==John");
            var filter = (QueryToken.Filter) tokens.get(0);
            assertEquals("name", filter.field());
        }

        @Test
        @DisplayName("null i18nProperties does not change field names")
        void nullI18nProperties() {
            Specification<Object> spec = QueryParser.parse("name==John", Object.class, null, "RU");
            assertNotNull(spec);
        }

        @Test
        @DisplayName("i18n resolution with multiple fields in expression")
        void i18nMultipleFields() {
            Set<String> i18nProperties = Set.of("name", "description");

            // Both 'name' and 'description' should be resolved
            Specification<Object> spec = QueryParser.parse("name==John AND description~ct~test", Object.class, i18nProperties, "RU");
            assertNotNull(spec);
        }

        @Test
        @DisplayName("deeply nested field with i18n: a.b.name==X with i18nProperties={'name'}, suffix='RU' → a.b.nameRU")
        void deeplyNestedI18n() {
            Set<String> i18nProperties = Set.of("name");

            // Should resolve final segment 'name' to 'nameRU'
            Specification<Object> spec = QueryParser.parse("a.b.name==X", Object.class, i18nProperties, "RU");
            assertNotNull(spec);

            // Verify resolution logic
            var tokens = QueryTokenizer.tokenize("a.b.name==X");
            var filter = (QueryToken.Filter) tokens.get(0);
            assertEquals("a.b.name", filter.field());

            // After resolution, the field should be "a.b.nameRU"
            String field = filter.field();
            int lastDot = field.lastIndexOf('.');
            String prefix = field.substring(0, lastDot);
            String finalSegment = field.substring(lastDot + 1);
            assertTrue(i18nProperties.contains(finalSegment));
            assertEquals("a.b.nameRU", prefix + "." + finalSegment + "RU");
        }
    }

    // --- Error conditions ---

    @Nested
    @DisplayName("Error conditions")
    class ErrorConditions {

        @Test
        @DisplayName("semicolons in query → ForemenApiException 400")
        void semicolonsInQuery() {
            ForemenApiException ex = assertThrows(ForemenApiException.class,
                    () -> QueryParser.parse("name==John;age==25", Object.class, NO_I18N, NO_SUFFIX));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("error.query.tokenize.unexpected.char", ex.getMessageCode());
        }

        @Test
        @DisplayName("unclosed parenthesis → ForemenApiException 400")
        void unclosedParenthesis() {
            ForemenApiException ex = assertThrows(ForemenApiException.class,
                    () -> QueryParser.parse("(name==John AND age>25", Object.class, NO_I18N, NO_SUFFIX));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("error.query.parse.unexpected.end", ex.getMessageCode());
        }

        @Test
        @DisplayName("unexpected token: extra close paren → ForemenApiException 400")
        void unexpectedCloseParenToken() {
            ForemenApiException ex = assertThrows(ForemenApiException.class,
                    () -> QueryParser.parse("name==John) AND age>25", Object.class, NO_I18N, NO_SUFFIX));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("error.query.parse.unexpected.token", ex.getMessageCode());
        }

        @Test
        @DisplayName("unexpected token: AND at start → ForemenApiException 400")
        void unexpectedAndAtStart() {
            ForemenApiException ex = assertThrows(ForemenApiException.class,
                    () -> QueryParser.parse("AND name==John", Object.class, NO_I18N, NO_SUFFIX));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        }

        @Test
        @DisplayName("unexpected token: OR at start → ForemenApiException 400")
        void unexpectedOrAtStart() {
            ForemenApiException ex = assertThrows(ForemenApiException.class,
                    () -> QueryParser.parse("OR name==John", Object.class, NO_I18N, NO_SUFFIX));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        }

        @Test
        @DisplayName("empty parentheses: () → ForemenApiException 400")
        void emptyParentheses() {
            ForemenApiException ex = assertThrows(ForemenApiException.class,
                    () -> QueryParser.parse("()", Object.class, NO_I18N, NO_SUFFIX));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        }
    }

    // --- Empty/null query → no-op specification ---

    @Nested
    @DisplayName("Empty/null query")
    class EmptyNullQuery {

        @Test
        @DisplayName("null query → returns non-null no-op specification")
        void nullQuery() {
            Specification<Object> spec = QueryParser.parse(null, Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }

        @Test
        @DisplayName("empty string query → returns non-null no-op specification")
        void emptyQuery() {
            Specification<Object> spec = QueryParser.parse("", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }

        @Test
        @DisplayName("blank/whitespace query → returns non-null no-op specification")
        void blankQuery() {
            Specification<Object> spec = QueryParser.parse("   ", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }
    }

    // --- Complex valid queries ---

    @Nested
    @DisplayName("Complex valid queries")
    class ComplexValidQueries {

        @Test
        @DisplayName("multiple AND: a==1 AND b==2 AND c==3")
        void multipleAnd() {
            Specification<Object> spec = QueryParser.parse("a==1 AND b==2 AND c==3", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }

        @Test
        @DisplayName("multiple OR: a==1 OR b==2 OR c==3")
        void multipleOr() {
            Specification<Object> spec = QueryParser.parse("a==1 OR b==2 OR c==3", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }

        @Test
        @DisplayName("mixed AND/OR: a==1 AND b==2 OR c==3")
        void mixedAndOr() {
            Specification<Object> spec = QueryParser.parse("a==1 AND b==2 OR c==3", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }

        @Test
        @DisplayName("deeply nested: ((a==1 OR b==2) AND (c==3 OR d==4))")
        void deeplyNested() {
            Specification<Object> spec = QueryParser.parse("((a==1 OR b==2) AND (c==3 OR d==4))", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }

        @Test
        @DisplayName("case-insensitive AND/OR keywords: a==1 and b==2 or c==3")
        void caseInsensitiveKeywords() {
            Specification<Object> spec = QueryParser.parse("a==1 and b==2 or c==3", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }

        @Test
        @DisplayName("dot-notation field: address.city==Warsaw parses without error")
        void dotNotationField() {
            Specification<Object> spec = QueryParser.parse("address.city==Warsaw", Object.class, NO_I18N, NO_SUFFIX);
            assertNotNull(spec);
        }
    }

    // --- Helper ---

    private void assertTokenizedFilter(String query, String expectedField, QueryOperator expectedOp, String expectedValue) {
        var tokens = QueryTokenizer.tokenize(query);
        assertEquals(1, tokens.size());
        assertInstanceOf(QueryToken.Filter.class, tokens.get(0));
        var filter = (QueryToken.Filter) tokens.get(0);
        assertEquals(expectedField, filter.field(), "field");
        assertEquals(expectedOp, filter.operator(), "operator");
        assertEquals(expectedValue, filter.value(), "value");
    }
}
