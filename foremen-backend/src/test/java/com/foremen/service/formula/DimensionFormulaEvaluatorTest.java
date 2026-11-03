package com.foremen.service.formula;

import com.foremen.exception.ForemenApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FOR-05-02 bug-report Round 5 #1 — the constants-only dimension value formula. */
class DimensionFormulaEvaluatorTest {

    @Test
    @DisplayName("=25+31+12 evaluates to 68.00")
    void sumOfConstants() {
        assertThat(DimensionFormulaEvaluator.evaluate("perimeter", "=25+31+12")).isEqualByComparingTo("68.00");
    }

    @Test
    @DisplayName("operators, parentheses, decimal comma and dot; leading = is optional; rounded to 2 decimals")
    void arithmeticSubset() {
        assertThat(DimensionFormulaEvaluator.evaluate("f", "=(2,5+0,2)*2")).isEqualByComparingTo("5.40");
        assertThat(DimensionFormulaEvaluator.evaluate("f", "=10/3")).isEqualByComparingTo("3.33");
        assertThat(DimensionFormulaEvaluator.evaluate("f", "4.5-1.25")).isEqualByComparingTo("3.25");
        assertThat(DimensionFormulaEvaluator.evaluate("f", " = 7 ")).isEqualByComparingTo("7.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "=", "=25+", "=25+abc", "=floorArea*2", "=X28+1", "=IFS(1>0,1)", "=1/0", "=2+(3"})
    @DisplayName("anything outside numbers / + - * / / parentheses is rejected with the field-carrying code")
    void rejectsInvalid(String formula) {
        assertThatThrownBy(() -> DimensionFormulaEvaluator.evaluate("ceilingHeight", formula))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining(DimensionFormulaEvaluator.FORMULA_INVALID_MESSAGE);
    }
}
