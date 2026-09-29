package com.foremen.service.formula;

import java.math.BigDecimal;
import java.util.Locale;

import com.foremen.dao.model.RoomEntity;

/**
 * The unit&rarr;dimension Volume fallback used when a work has <b>no applicable volume formula</b>
 * (neither a per-package override AST nor a default formula), per FOR-05-05 Requirement 5 and the
 * design's Component B5.
 *
 * <p>When no formula applies, the cell's Volume is the room dimension whose conventional unit
 * matches the work's measurement unit (R5.1, R5.2):
 * <ul>
 *   <li>{@code m2} &rarr; {@code floorArea} (an area unit maps to the room's floor area; when the
 *       area unit maps ambiguously to more than one dimension the documented default is
 *       {@code floorArea}, R5.3);</li>
 *   <li>{@code m} / {@code mb} (linear metre / bieżący metre) &rarr; {@code perimeter} (a length
 *       unit maps to the room's perimeter; the catalog seeds the linear unit as {@code mb}, so both
 *       spellings map to the same dimension);</li>
 *   <li>{@code szt} (count) &rarr; {@code 1} per room &mdash; a unit-quantity assignment (R5.2);</li>
 *   <li>anything else (unmapped unit, or a mapped dimension that the room leaves unset) &rarr;
 *       {@code 0}; the cell then shows the needs-attention state (R5.4).</li>
 * </ul>
 *
 * <p>The resolver is pure, total and deterministic: it performs no I/O, holds no state, never
 * persists anything, and always yields the same result for the same {@code (unitCode, room)}. A
 * {@code null} unit code or a {@code null} room resolves to {@link BigDecimal#ZERO} so the function
 * is total. Everything lives in {@code static} helpers so it is property-testable without Spring.
 */
public final class VolumeFallbackResolver {

    private VolumeFallbackResolver() {
    }

    /**
     * Resolves the fallback Volume for a work of measurement unit {@code unitCode} in {@code room}.
     *
     * <p>The comparison is case-insensitive and trims surrounding whitespace so a {@code "M2"} or
     * {@code " mb "} still maps. A {@code szt} (count) unit always resolves to {@code 1} regardless
     * of the room's dimensions. An area/length unit resolves to the corresponding room dimension, or
     * {@code 0} when that dimension is unset (mirroring the formula engine's missing-variable-is-zero
     * contract, R5.4). An unmapped unit resolves to {@code 0}.
     *
     * @param unitCode the work's {@code MeasurementUnitEntity.code} (e.g. {@code m2}, {@code mb},
     *                 {@code szt}); may be {@code null}
     * @param room     the room whose dimensions supply the fallback value; may be {@code null}
     * @return the resolved fallback Volume, never {@code null}
     */
    public static BigDecimal resolve(String unitCode, RoomEntity room) {
        String normalized = normalize(unitCode);
        if (normalized == null) {
            return BigDecimal.ZERO;
        }

        return switch (normalized) {
            // Count: one unit per room (R5.2), independent of the room's measured dimensions.
            case "szt" -> BigDecimal.ONE;
            // Area: floor area is the documented default for the ambiguous m2 mapping (R5.1, R5.3).
            case "m2" -> nonNull(room == null ? null : room.getFloorArea());
            // Length: perimeter. The catalog seeds the linear unit as "mb"; "m" is accepted too.
            case "m", "mb" -> nonNull(room == null ? null : room.getPerimeter());
            // No room dimension matches the work's unit (R5.4).
            default -> BigDecimal.ZERO;
        };
    }

    /**
     * Reports whether {@code unitCode} maps to a known room dimension (i.e. resolves via a defined
     * mapping rather than the {@code 0} unmapped default). {@code szt}, {@code m2}, {@code m} and
     * {@code mb} are mapped; everything else (and {@code null}) is unmapped.
     *
     * @param unitCode the work's measurement unit code; may be {@code null}
     * @return {@code true} if the unit has a defined dimension mapping, {@code false} otherwise
     */
    public static boolean isMapped(String unitCode) {
        String normalized = normalize(unitCode);
        if (normalized == null) {
            return false;
        }
        return switch (normalized) {
            case "szt", "m2", "m", "mb" -> true;
            default -> false;
        };
    }

    private static String normalize(String unitCode) {
        if (unitCode == null) {
            return null;
        }
        String trimmed = unitCode.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
