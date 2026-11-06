package com.foremen.service.roomsimport.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured output of the measuring call (design §B4 stage 4, §B5a / Requirement 25). Every numeric
 * value carries its own source unit as a {@link Measured}; vertices are in {@code geometryUnit}.
 * Conversion to the target units is done by the backend, never by the model. Counts
 * ({@code internalCorners}, opening {@code count}) are plain integers.
 *
 * @param stated values written in the source keyed by field name (e.g. {@code floorArea},
 *               {@code perimeter}); not authoritative. Count fields ({@code doorCount},
 *               {@code windowCount}) are {@link Measured} with a {@code null} unit.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MeasureResponse(String geometryUnit, String geometryUnitSource, List<Vertex> vertices,
                              List<Wall> walls, Measured ceilingHeight, Integer internalCorners,
                              Measured narrowPlace, Map<String, Measured> stated, Boolean scaleEstablished,
                              Double confidence) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vertex(Double x, Double y) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Wall(Measured wallGap, Measured finishGap, List<Opening> openings) {}

    /** {@code type} is {@code DOOR} or {@code WINDOW}; height, width and offset carry their own length unit. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Opening(String type, Integer count, Measured height, Measured width, Measured offset) {}

    public MeasureResponse {
        vertices = vertices == null ? List.of() : Collections.unmodifiableList(new java.util.ArrayList<>(vertices));
        walls = walls == null ? List.of() : Collections.unmodifiableList(new java.util.ArrayList<>(walls));
        stated = stated == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stated));
    }
}
