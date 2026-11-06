package com.foremen.service.roomsimport.units;

import com.foremen.dao.model.RoomGeometry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Input of {@link PlausibilityCorrector} (FOR-05-02, Requirement 25), independent of the AI DTOs.
 * All numbers are already converted to target units (m, m², pieces); {@code unit} on a
 * {@link Quantity} is the <em>source</em> unit before conversion and is only used for warnings.
 *
 * @param roomTempId   draft room id used on emitted warnings
 * @param values       scalar values by field name (see the constants below); absent/null value = unknown
 * @param geometry     converted geometry (vertices, walls with gaps, openings), or {@code null}
 * @param geometryUnit source unit of the geometry numbers (for warnings), may be {@code null}
 */
public record PlausibilityInput(String roomTempId, Map<String, Quantity> values, RoomGeometry geometry,
                                String geometryUnit) {

    public static final String CEILING_HEIGHT = "ceilingHeight";
    public static final String NARROW_PLACE = "narrowPlace";
    public static final String FLOOR_AREA = "floorArea";
    public static final String WALL_AREA = "wallArea";
    public static final String PERIMETER = "perimeter";
    public static final String DOOR_AREA = "doorArea";
    public static final String WINDOW_AREA = "windowArea";
    public static final String DOOR_HEIGHT = "doorHeight";
    public static final String DOOR_WIDTH = "doorWidth";
    public static final String WINDOW_HEIGHT = "windowHeight";
    public static final String WINDOW_WIDTH = "windowWidth";
    public static final String DOOR_COUNT = "doorCount";
    public static final String WINDOW_COUNT = "windowCount";

    /** A converted number plus the unit it was originally reported in. */
    public record Quantity(Double value, String unit) {}

    public PlausibilityInput {
        values = values == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Value of {@code field}, or {@code null}. */
    public Double value(String field) {
        Quantity q = values.get(field);
        return q == null ? null : q.value();
    }

    /** Source unit of {@code field}, or {@code null}. */
    public String unit(String field) {
        Quantity q = values.get(field);
        return q == null ? null : q.unit();
    }
}
