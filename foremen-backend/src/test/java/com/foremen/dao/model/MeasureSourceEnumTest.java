package com.foremen.dao.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link MeasureSource} enum.
 * Validates: Requirements 1.6
 */
class MeasureSourceEnumTest {

    @Test
    @DisplayName("values() returns exactly CALCULATED and MANUAL in declaration order")
    void valuesAreExactlyTheTwoSourcesInDeclarationOrder() {
        assertThat(MeasureSource.values()).containsExactly(
                MeasureSource.CALCULATED,
                MeasureSource.MANUAL
        );
    }

    @Test
    @DisplayName("enum defines no values beyond the two expected ones")
    void definesExactlyTwoValues() {
        assertThat(MeasureSource.values()).hasSize(2);
    }
}
