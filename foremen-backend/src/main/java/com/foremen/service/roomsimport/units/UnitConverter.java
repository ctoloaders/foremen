package com.foremen.service.roomsimport.units;

import java.util.Locale;
import java.util.Optional;

/**
 * Pure converter from a value's source unit to the unit its field uses (FOR-05-02, Requirement 25,
 * design B5a). Lengths go to metres ({@code m=1, cm=0.01, mm=0.001, ft=0.3048}), areas to square
 * metres ({@code m2=1, cm2=1e-4, mm2=1e-6}). A length unit on an area field (or the reverse) is an
 * error and is never guessed; an unknown unit is also an error.
 */
public final class UnitConverter {

    /** Whether a field holds a length (m), an area (m²) or a plain count. */
    public enum Dimension { LENGTH, AREA }

    /** Conversion outcome: either a converted {@code value} or an {@code error}. */
    public record Result(Double value, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** Error code: unit belongs to another dimension than the field. */
    public static final String DIMENSION_MISMATCH = "DIMENSION_MISMATCH";
    /** Error code: unit string not recognised. */
    public static final String UNKNOWN_UNIT = "UNKNOWN_UNIT";
    /** Error code: value is null or not finite. */
    public static final String INVALID_VALUE = "INVALID_VALUE";

    /** Metres per source length unit, or empty when {@code unit} is not a length unit. */
    public Optional<Double> lengthFactor(String unit) {
        return switch (normalize(unit)) {
            case "m" -> Optional.of(1.0);
            case "cm" -> Optional.of(0.01);
            case "mm" -> Optional.of(0.001);
            case "ft" -> Optional.of(0.3048);
            default -> Optional.empty();
        };
    }

    /** Square metres per source area unit, or empty when {@code unit} is not an area unit. */
    public Optional<Double> areaFactor(String unit) {
        return switch (normalize(unit)) {
            case "m2" -> Optional.of(1.0);
            case "cm2" -> Optional.of(1e-4);
            case "mm2" -> Optional.of(1e-6);
            default -> Optional.empty();
        };
    }

    /**
     * Converts {@code value} given in {@code unit} to the field's target unit (m for
     * {@link Dimension#LENGTH}, m² for {@link Dimension#AREA}). Never throws.
     */
    public Result convert(Double value, String unit, Dimension target) {
        if (value == null || !Double.isFinite(value)) {
            return new Result(null, INVALID_VALUE);
        }
        Optional<Double> factor = target == Dimension.LENGTH ? lengthFactor(unit) : areaFactor(unit);
        if (factor.isPresent()) {
            return new Result(value * factor.get(), null);
        }
        boolean otherDimension = target == Dimension.LENGTH ? areaFactor(unit).isPresent()
                : lengthFactor(unit).isPresent();
        return new Result(null, otherDimension ? DIMENSION_MISMATCH : UNKNOWN_UNIT);
    }

    private static String normalize(String unit) {
        if (unit == null) {
            return "";
        }
        String u = unit.trim().toLowerCase(Locale.ROOT).replace("²", "2").replace("^2", "2")
                .replace(" ", "");
        return switch (u) {
            case "metre", "metres", "meter", "meters" -> "m";
            case "feet", "foot" -> "ft";
            default -> u;
        };
    }
}
