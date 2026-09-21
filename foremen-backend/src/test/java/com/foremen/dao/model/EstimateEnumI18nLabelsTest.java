package com.foremen.dao.model;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for the i18n labels of the FOR-05-03 estimate enums
 * {@link EstimateStatus} and {@link DiscountKind}.
 *
 * <p>Property 13: i18n parity — every enum value exposes non-empty {@code nameRU} and
 * {@code namePL} labels, and {@link EstimateStatus#label()}/{@link DiscountKind#label()}
 * never surface a raw enum key.
 *
 * Validates: Requirements 11.1, 11.2, 11.3
 */
class EstimateEnumI18nLabelsTest {

    @ParameterizedTest
    @EnumSource(EstimateStatus.class)
    @DisplayName("every EstimateStatus value has non-empty RU and PL labels")
    void estimateStatusHasNonEmptyRuAndPlLabels(EstimateStatus status) {
        assertThat(status.getNameRU())
                .as("nameRU of %s", status.name())
                .isNotNull()
                .isNotBlank();
        assertThat(status.getNamePL())
                .as("namePL of %s", status.name())
                .isNotNull()
                .isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(DiscountKind.class)
    @DisplayName("every DiscountKind value has non-empty RU and PL labels")
    void discountKindHasNonEmptyRuAndPlLabels(DiscountKind kind) {
        assertThat(kind.getNameRU())
                .as("nameRU of %s", kind.name())
                .isNotNull()
                .isNotBlank();
        assertThat(kind.getNamePL())
                .as("namePL of %s", kind.name())
                .isNotNull()
                .isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(EstimateStatus.class)
    @DisplayName("EstimateStatus.label() prefers PL and never surfaces the raw enum key")
    void estimateStatusLabelPrefersPlAndIsNotRawKey(EstimateStatus status) {
        assertThat(status.label())
                .as("label() of %s", status.name())
                .isEqualTo(status.getNamePL())
                .isNotEqualTo(status.name());
    }

    @ParameterizedTest
    @EnumSource(DiscountKind.class)
    @DisplayName("DiscountKind.label() prefers PL and never surfaces the raw enum key")
    void discountKindLabelPrefersPlAndIsNotRawKey(DiscountKind kind) {
        assertThat(kind.label())
                .as("label() of %s", kind.name())
                .isEqualTo(kind.getNamePL())
                .isNotEqualTo(kind.name());
    }
}
