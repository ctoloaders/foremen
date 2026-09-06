package com.foremen.service.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QueryOperator enum")
class QueryOperatorTest {

    @Test
    @DisplayName("should have exactly 19 operators defined")
    void shouldHaveExactly19Operators() {
        assertEquals(23, QueryOperator.values().length);
    }

    @Test
    @DisplayName("each operator should have the correct symbol")
    void eachOperatorShouldHaveCorrectSymbol() {
        assertEquals("==", QueryOperator.EQUALS.getSymbol());
        assertEquals("!=", QueryOperator.NOT_EQUALS.getSymbol());
        assertEquals("~ct~", QueryOperator.CONTAINS.getSymbol());
        assertEquals("~sw~", QueryOperator.STARTS_WITH.getSymbol());
        assertEquals("~ew~", QueryOperator.ENDS_WITH.getSymbol());
        assertEquals("~CT~", QueryOperator.CONTAINS_CS.getSymbol());
        assertEquals("~SW~", QueryOperator.STARTS_WITH_CS.getSymbol());
        assertEquals("~EW~", QueryOperator.ENDS_WITH_CS.getSymbol());
        assertEquals("~~", QueryOperator.LIKE.getSymbol());
        assertEquals(">", QueryOperator.GREATER_THAN.getSymbol());
        assertEquals("<", QueryOperator.LESS_THAN.getSymbol());
        assertEquals(">=", QueryOperator.GREATER_THAN_OR_EQUAL.getSymbol());
        assertEquals("<=", QueryOperator.LESS_THAN_OR_EQUAL.getSymbol());
        assertEquals(">date", QueryOperator.GT_DATE.getSymbol());
        assertEquals("<date", QueryOperator.LT_DATE.getSymbol());
        assertEquals("~in~", QueryOperator.IN.getSymbol());
        assertEquals("~notin~", QueryOperator.NOT_IN.getSymbol());
        assertEquals("~null~", QueryOperator.NULL.getSymbol());
        assertEquals("~notnull~", QueryOperator.NOT_NULL.getSymbol());
    }

    @Test
    @DisplayName("ORDERED_FOR_MATCHING should contain all 20 operators")
    void orderedForMatchingShouldContainAllOperators() {
        List<QueryOperator> ordered = QueryOperator.ORDERED_FOR_MATCHING;
        Set<QueryOperator> allOperators = new HashSet<>(Arrays.asList(QueryOperator.values()));
        Set<QueryOperator> orderedSet = new HashSet<>(ordered);

        assertEquals(allOperators, orderedSet,
                "ORDERED_FOR_MATCHING must contain all operators, no more and no less");
        assertEquals(QueryOperator.values().length, ordered.size(),
                "ORDERED_FOR_MATCHING should have the same number of entries as the enum");
    }

    @Test
    @DisplayName("ORDERED_FOR_MATCHING should place longer symbol before shorter when one is a prefix of another")
    void orderedForMatchingShouldPreventPartialMatches() {
        List<QueryOperator> ordered = QueryOperator.ORDERED_FOR_MATCHING;

        for (int i = 0; i < ordered.size(); i++) {
            for (int j = i + 1; j < ordered.size(); j++) {
                String symbolI = ordered.get(i).getSymbol();
                String symbolJ = ordered.get(j).getSymbol();

                // If symbolJ is a prefix of symbolI, that's fine (longer is first)
                // If symbolI is a prefix of symbolJ, that's a problem (shorter before longer)
                if (symbolJ.startsWith(symbolI)) {
                    fail(String.format(
                            "Operator %s (symbol '%s') at index %d is a prefix of %s (symbol '%s') at index %d. " +
                                    "The longer symbol must appear first to prevent partial matches.",
                            ordered.get(i).name(), symbolI, i,
                            ordered.get(j).name(), symbolJ, j));
                }
            }
        }
    }

    @Test
    @DisplayName("no two operators should share the same symbol")
    void noTwoOperatorsShouldShareSameSymbol() {
        QueryOperator[] operators = QueryOperator.values();
        Set<String> symbols = new HashSet<>();

        for (QueryOperator op : operators) {
            boolean added = symbols.add(op.getSymbol());
            assertTrue(added, "Duplicate symbol found: " + op.getSymbol() + " on operator " + op.name());
        }

        assertEquals(operators.length, symbols.size());
    }
}
