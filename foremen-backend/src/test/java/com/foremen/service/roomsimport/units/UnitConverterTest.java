package com.foremen.service.roomsimport.units;

import com.foremen.service.roomsimport.units.UnitConverter.Dimension;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** FOR-05-02 task 9.2 — Requirement 25.2/25.3: pure unit conversion. */
class UnitConverterTest {

    private final UnitConverter converter = new UnitConverter();

    @Test
    void convertsLengthsToMetres() {
        assertThat(converter.convert(1.0, "m", Dimension.LENGTH).value()).isEqualTo(1.0);
        assertThat(converter.convert(260.0, "cm", Dimension.LENGTH).value()).isCloseTo(2.6, within(1e-12));
        assertThat(converter.convert(2600.0, "mm", Dimension.LENGTH).value()).isCloseTo(2.6, within(1e-12));
        assertThat(converter.convert(10.0, "ft", Dimension.LENGTH).value()).isCloseTo(3.048, within(1e-12));
    }

    @Test
    void convertsAreasToSquareMetres() {
        assertThat(converter.convert(13.12, "m2", Dimension.AREA).value()).isEqualTo(13.12);
        assertThat(converter.convert(131200.0, "cm2", Dimension.AREA).value()).isCloseTo(13.12, within(1e-9));
        assertThat(converter.convert(13120000.0, "mm2", Dimension.AREA).value()).isCloseTo(13.12, within(1e-9));
    }

    @Test
    void acceptsCaseAndSuperscriptVariants() {
        assertThat(converter.convert(2.0, " MM ", Dimension.LENGTH).value()).isCloseTo(0.002, within(1e-12));
        assertThat(converter.convert(1000000.0, "mm²", Dimension.AREA).value()).isCloseTo(1.0, within(1e-12));
        assertThat(converter.convert(1.0, "m^2", Dimension.AREA).value()).isEqualTo(1.0);
    }

    @Test
    void lengthUnitOnAreaFieldIsAnErrorNotGuessed() {
        var r = converter.convert(13.12, "m", Dimension.AREA);
        assertThat(r.ok()).isFalse();
        assertThat(r.value()).isNull();
        assertThat(r.error()).isEqualTo(UnitConverter.DIMENSION_MISMATCH);
    }

    @Test
    void areaUnitOnLengthFieldIsAnError() {
        var r = converter.convert(2.6, "m2", Dimension.LENGTH);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isEqualTo(UnitConverter.DIMENSION_MISMATCH);
    }

    @Test
    void unknownNullAndNonFiniteInputsAreErrors() {
        assertThat(converter.convert(1.0, "furlong", Dimension.LENGTH).error()).isEqualTo(UnitConverter.UNKNOWN_UNIT);
        assertThat(converter.convert(1.0, null, Dimension.LENGTH).error()).isEqualTo(UnitConverter.UNKNOWN_UNIT);
        assertThat(converter.convert(null, "m", Dimension.LENGTH).error()).isEqualTo(UnitConverter.INVALID_VALUE);
        assertThat(converter.convert(Double.NaN, "m", Dimension.LENGTH).error()).isEqualTo(UnitConverter.INVALID_VALUE);
        assertThat(converter.convert(Double.POSITIVE_INFINITY, "m", Dimension.AREA).error())
                .isEqualTo(UnitConverter.INVALID_VALUE);
    }
}
