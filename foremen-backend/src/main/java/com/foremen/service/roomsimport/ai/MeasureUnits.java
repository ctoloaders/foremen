package com.foremen.service.roomsimport.ai;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Unit vocabulary and per-field kinds of the measure response (design §B5a, Requirement 25). */
public final class MeasureUnits {

    public static final List<String> LENGTH_UNITS = List.of("m", "cm", "mm", "ft");
    public static final List<String> AREA_UNITS = List.of("m2", "cm2", "mm2");
    public static final List<String> UNIT_SOURCES = List.of(Measured.EXPLICIT, Measured.INFERRED);

    /** Kind of a {@code stated} field. */
    public enum Kind { LENGTH, AREA, COUNT }

    /** Known {@code stated} fields and their kind (insertion order is the schema order). */
    public static final Map<String, Kind> STATED_FIELDS = Map.ofEntries(
            Map.entry("floorArea", Kind.AREA), Map.entry("wallArea", Kind.AREA),
            Map.entry("doorArea", Kind.AREA), Map.entry("windowArea", Kind.AREA),
            Map.entry("perimeter", Kind.LENGTH), Map.entry("ceilingHeight", Kind.LENGTH),
            Map.entry("doorHeight", Kind.LENGTH), Map.entry("doorWidth", Kind.LENGTH),
            Map.entry("windowHeight", Kind.LENGTH), Map.entry("windowWidth", Kind.LENGTH),
            Map.entry("doorCount", Kind.COUNT), Map.entry("windowCount", Kind.COUNT));

    private static final Set<String> LENGTH_SET = Set.copyOf(LENGTH_UNITS);
    private static final Set<String> AREA_SET = Set.copyOf(AREA_UNITS);

    private MeasureUnits() {}

    public static boolean isLengthUnit(String u) {
        return u != null && LENGTH_SET.contains(u);
    }

    public static boolean isAreaUnit(String u) {
        return u != null && AREA_SET.contains(u);
    }
}
