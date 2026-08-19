package com.foremen.service.query;

import com.foremen.exception.ForemenApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QueryTokenizer")
class QueryTokenizerTest {

    // --- Simple filter expressions ---

    @Nested
    @DisplayName("Simple filter tokenization")
    class SimpleFilters {

        @Test
        @DisplayName("simple equality: name==John")
        void simpleEquality() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("name==John");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "name", QueryOperator.EQUALS, "John");
        }

        @Test
        @DisplayName("simple inequality: age!=25")
        void simpleInequality() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("age!=25");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "age", QueryOperator.NOT_EQUALS, "25");
        }

        @Test
        @DisplayName("contains operator: name~ct~john")
        void containsOperator() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("name~ct~john");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "name", QueryOperator.CONTAINS, "john");
        }

        @Test
        @DisplayName("starts with: name~sw~jo")
        void startsWithOperator() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("name~sw~jo");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "name", QueryOperator.STARTS_WITH, "jo");
        }

        @Test
        @DisplayName("ends with: name~ew~hn")
        void endsWithOperator() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("name~ew~hn");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "name", QueryOperator.ENDS_WITH, "hn");
        }

        @Test
        @DisplayName("case-sensitive contains: code~CT~ABC")
        void caseSensitiveContains() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("code~CT~ABC");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "code", QueryOperator.CONTAINS_CS, "ABC");
        }

        @Test
        @DisplayName("case-sensitive starts with: code~SW~PRJ")
        void caseSensitiveStartsWith() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("code~SW~PRJ");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "code", QueryOperator.STARTS_WITH_CS, "PRJ");
        }

        @Test
        @DisplayName("case-sensitive ends with: code~EW~01")
        void caseSensitiveEndsWith() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("code~EW~01");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "code", QueryOperator.ENDS_WITH_CS, "01");
        }

        @Test
        @DisplayName("legacy LIKE: name~~john")
        void legacyLike() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("name~~john");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "name", QueryOperator.LIKE, "john");
        }
    }

    @Nested
    @DisplayName("Numeric operators")
    class NumericOperators {

        @Test
        @DisplayName("greater than: age>25")
        void greaterThan() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("age>25");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "age", QueryOperator.GREATER_THAN, "25");
        }

        @Test
        @DisplayName("less than: age<100")
        void lessThan() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("age<100");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "age", QueryOperator.LESS_THAN, "100");
        }

        @Test
        @DisplayName("greater than or equal: age>=18")
        void greaterThanOrEqual() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("age>=18");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "age", QueryOperator.GREATER_THAN_OR_EQUAL, "18");
        }

        @Test
        @DisplayName("less than or equal: age<=65")
        void lessThanOrEqual() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("age<=65");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "age", QueryOperator.LESS_THAN_OR_EQUAL, "65");
        }
    }

    @Nested
    @DisplayName("Date operators")
    class DateOperators {

        @Test
        @DisplayName("greater than date: date>date2024-01-01")
        void greaterThanDate() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("date>date2024-01-01");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "date", QueryOperator.GT_DATE, "2024-01-01");
        }

        @Test
        @DisplayName("less than date: date<date2024-12-31")
        void lessThanDate() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("date<date2024-12-31");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "date", QueryOperator.LT_DATE, "2024-12-31");
        }
    }

    @Nested
    @DisplayName("Set membership operators")
    class SetMembershipOperators {

        @Test
        @DisplayName("IN operator: status~in~active,pending")
        void inOperator() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("status~in~active,pending");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "status", QueryOperator.IN, "active,pending");
        }

        @Test
        @DisplayName("NOT_IN operator: type~notin~draft,archived")
        void notInOperator() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("type~notin~draft,archived");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "type", QueryOperator.NOT_IN, "draft,archived");
        }
    }

    @Nested
    @DisplayName("Null check operators")
    class NullCheckOperators {

        @Test
        @DisplayName("NULL operator: deletedAt~null~")
        void nullOperator() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("deletedAt~null~");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "deletedAt", QueryOperator.NULL, "");
        }

        @Test
        @DisplayName("NOT_NULL operator: assignee~notnull~")
        void notNullOperator() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("assignee~notnull~");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "assignee", QueryOperator.NOT_NULL, "");
        }
    }

    // --- AND/OR keywords ---

    @Nested
    @DisplayName("AND/OR keyword tokenization")
    class AndOrKeywords {

        @Test
        @DisplayName("AND keyword: name==John AND age>25")
        void andKeyword() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("name==John AND age>25");

            assertEquals(3, tokens.size());
            assertFilter(tokens.get(0), "name", QueryOperator.EQUALS, "John");
            assertInstanceOf(QueryToken.And.class, tokens.get(1));
            assertFilter(tokens.get(2), "age", QueryOperator.GREATER_THAN, "25");
        }

        @Test
        @DisplayName("OR keyword: name==John OR name==Jane")
        void orKeyword() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("name==John OR name==Jane");

            assertEquals(3, tokens.size());
            assertFilter(tokens.get(0), "name", QueryOperator.EQUALS, "John");
            assertInstanceOf(QueryToken.Or.class, tokens.get(1));
            assertFilter(tokens.get(2), "name", QueryOperator.EQUALS, "Jane");
        }

        @Test
        @DisplayName("case-insensitive AND: a==1 and b==2")
        void caseInsensitiveAnd() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("a==1 and b==2");

            assertEquals(3, tokens.size());
            assertFilter(tokens.get(0), "a", QueryOperator.EQUALS, "1");
            assertInstanceOf(QueryToken.And.class, tokens.get(1));
            assertFilter(tokens.get(2), "b", QueryOperator.EQUALS, "2");
        }

        @Test
        @DisplayName("case-insensitive OR: a==1 or b==2")
        void caseInsensitiveOr() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("a==1 or b==2");

            assertEquals(3, tokens.size());
            assertFilter(tokens.get(0), "a", QueryOperator.EQUALS, "1");
            assertInstanceOf(QueryToken.Or.class, tokens.get(1));
            assertFilter(tokens.get(2), "b", QueryOperator.EQUALS, "2");
        }
    }

    // --- Parentheses ---

    @Nested
    @DisplayName("Parentheses tokenization")
    class Parentheses {

        @Test
        @DisplayName("parenthesized group: (name==John OR age>25)")
        void parenthesizedGroup() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("(name==John OR age>25)");

            assertEquals(5, tokens.size());
            assertInstanceOf(QueryToken.OpenParen.class, tokens.get(0));
            assertFilter(tokens.get(1), "name", QueryOperator.EQUALS, "John");
            assertInstanceOf(QueryToken.Or.class, tokens.get(2));
            assertFilter(tokens.get(3), "age", QueryOperator.GREATER_THAN, "25");
            assertInstanceOf(QueryToken.CloseParen.class, tokens.get(4));
        }

        @Test
        @DisplayName("nested parentheses: (a==1 AND (b==2 OR c==3))")
        void nestedParentheses() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("(a==1 AND (b==2 OR c==3))");

            assertEquals(9, tokens.size());
            assertInstanceOf(QueryToken.OpenParen.class, tokens.get(0));
            assertFilter(tokens.get(1), "a", QueryOperator.EQUALS, "1");
            assertInstanceOf(QueryToken.And.class, tokens.get(2));
            assertInstanceOf(QueryToken.OpenParen.class, tokens.get(3));
            assertFilter(tokens.get(4), "b", QueryOperator.EQUALS, "2");
            assertInstanceOf(QueryToken.Or.class, tokens.get(5));
            assertFilter(tokens.get(6), "c", QueryOperator.EQUALS, "3");
            assertInstanceOf(QueryToken.CloseParen.class, tokens.get(7));
            assertInstanceOf(QueryToken.CloseParen.class, tokens.get(8));
        }
    }

    // --- Semicolon rejection ---

    @Nested
    @DisplayName("Semicolon rejection")
    class SemicolonRejection {

        @Test
        @DisplayName("semicolon in middle: name==John;age==25 → throws ForemenApiException 400")
        void semicolonInMiddle() {
            ForemenApiException ex = assertThrows(ForemenApiException.class,
                    () -> QueryTokenizer.tokenize("name==John;age==25"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("error.query.tokenize.unexpected.char", ex.getMessageCode());
        }

        @Test
        @DisplayName("semicolon at start: ;name==John → throws ForemenApiException 400")
        void semicolonAtStart() {
            ForemenApiException ex = assertThrows(ForemenApiException.class,
                    () -> QueryTokenizer.tokenize(";name==John"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("error.query.tokenize.unexpected.char", ex.getMessageCode());
        }
    }

    // --- Complex queries ---

    @Nested
    @DisplayName("Complex query tokenization")
    class ComplexQueries {

        @Test
        @DisplayName("complex: (name~ct~john OR name~ct~jane) AND status==active")
        void complexQuery() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("(name~ct~john OR name~ct~jane) AND status==active");

            assertEquals(7, tokens.size());
            assertInstanceOf(QueryToken.OpenParen.class, tokens.get(0));
            assertFilter(tokens.get(1), "name", QueryOperator.CONTAINS, "john");
            assertInstanceOf(QueryToken.Or.class, tokens.get(2));
            assertFilter(tokens.get(3), "name", QueryOperator.CONTAINS, "jane");
            assertInstanceOf(QueryToken.CloseParen.class, tokens.get(4));
            assertInstanceOf(QueryToken.And.class, tokens.get(5));
            assertFilter(tokens.get(6), "status", QueryOperator.EQUALS, "active");
        }

        @Test
        @DisplayName("dot-notation field: address.city==Warsaw")
        void dotNotationField() {
            List<QueryToken> tokens = QueryTokenizer.tokenize("address.city==Warsaw");

            assertEquals(1, tokens.size());
            assertFilter(tokens.get(0), "address.city", QueryOperator.EQUALS, "Warsaw");
        }
    }

    // --- Helper assertion method ---

    private void assertFilter(QueryToken token, String expectedField, QueryOperator expectedOperator, String expectedValue) {
        assertInstanceOf(QueryToken.Filter.class, token);
        QueryToken.Filter filter = (QueryToken.Filter) token;
        assertEquals(expectedField, filter.field(), "field");
        assertEquals(expectedOperator, filter.operator(), "operator");
        assertEquals(expectedValue, filter.value(), "value");
    }
}
