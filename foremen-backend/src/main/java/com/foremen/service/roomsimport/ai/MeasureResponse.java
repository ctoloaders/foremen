package com.foremen.service.roomsimport.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured output of the measuring call (design §B4 stage 4). Coordinates are in {@code unit};
 * conversion to metres is done by {@code GeometryNormalizer}.
 *
 * @param stated values written in the source (e.g. {@code floorArea}, {@code perimeter}); not authoritative
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MeasureResponse(String unit, List<Vertex> vertices, List<Wall> walls,
                              BigDecimal ceilingHeight, Integer internalCorners, BigDecimal narrowPlace,
                              Map<String, Object> stated, Boolean scaleEstablished, Double confidence) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vertex(Double x, Double y) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Wall(Double wallGap, Double finishGap, List<Opening> openings) {}

    /** {@code type} is {@code DOOR} or {@code WINDOW}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Opening(String type, Integer count, Double height, Double width, Double offset) {}

    public MeasureResponse {
        vertices = vertices == null ? List.of() : Collections.unmodifiableList(new java.util.ArrayList<>(vertices));
        walls = walls == null ? List.of() : Collections.unmodifiableList(new java.util.ArrayList<>(walls));
        stated = stated == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stated));
    }
}
