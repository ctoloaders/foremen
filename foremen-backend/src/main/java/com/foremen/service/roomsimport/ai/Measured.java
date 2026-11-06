package com.foremen.service.roomsimport.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A number exactly as written in the source plus its unit (Requirement 25).
 *
 * @param unit lengths {@code m|cm|mm|ft}, areas {@code m2|cm2|mm2}; {@code null} only for counts
 * @param unitSource {@code EXPLICIT} (written next to the number) or {@code INFERRED}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Measured(Double value, String unit, String unitSource) {

    public static final String EXPLICIT = "EXPLICIT";
    public static final String INFERRED = "INFERRED";

    public static Measured of(double value, String unit, String unitSource) {
        return new Measured(value, unit, unitSource);
    }

    public static Measured explicit(double value, String unit) {
        return new Measured(value, unit, EXPLICIT);
    }

    public static Measured inferred(double value, String unit) {
        return new Measured(value, unit, INFERRED);
    }

    /** Raw value of {@code m}, or {@code null} when {@code m} is null. */
    public static Double valueOf(Measured m) {
        return m == null ? null : m.value();
    }
}
