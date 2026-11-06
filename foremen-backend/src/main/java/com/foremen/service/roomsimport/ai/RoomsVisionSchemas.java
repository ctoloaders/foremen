package com.foremen.service.roomsimport.ai;

import com.foremen.service.roomsimport.ai.MeasureUnits.Kind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON schemas (JSON-Schema subset accepted by Gemini structured output) for the detect and
 * measure responses; they mirror {@link DetectResponse} and {@link MeasureResponse}.
 */
public final class RoomsVisionSchemas {

    private RoomsVisionSchemas() {}

    /** Schema of {@link DetectResponse}. */
    public static Map<String, Object> detect() {
        Map<String, Object> sourceRef = obj(
                props("fileName", str(), "page", nullable(integer())), List.of("fileName"));
        Map<String, Object> bbox = new LinkedHashMap<>();
        bbox.put("type", "array");
        bbox.put("items", num());
        bbox.put("minItems", 4);
        bbox.put("maxItems", 4);
        bbox.put("nullable", true);
        Map<String, Object> room = obj(
                props("tempId", str(), "label", str(), "typeHint", nullable(str()),
                        "roomTypeId", nullable(integer()), "sourceRef", sourceRef, "bbox", bbox,
                        "confidence", num()),
                List.of("tempId", "label", "confidence"));
        return obj(props("rooms", array(room),
                "statedRoomCount", nullable(integer()), "notes", nullable(str())),
                List.of("rooms"));
    }

    /** Schema of {@link MeasureResponse}. */
    public static Map<String, Object> measure() {
        Map<String, Object> vertex = obj(props("x", num(), "y", num()), List.of("x", "y"));
        Map<String, Object> opening = obj(
                props("type", Map.of("type", "string", "enum", List.of("DOOR", "WINDOW")),
                        "count", integer(), "height", measured(Kind.LENGTH), "width", measured(Kind.LENGTH),
                        "offset", nullable(measured(Kind.LENGTH))),
                List.of("type", "count", "height", "width"));
        Map<String, Object> wall = obj(
                props("wallGap", nullable(measured(Kind.LENGTH)), "finishGap", nullable(measured(Kind.LENGTH)),
                        "openings", array(opening)),
                List.of());
        Map<String, Object> statedProps = new LinkedHashMap<>();
        new TreeMap<>(MeasureUnits.STATED_FIELDS)
                .forEach((name, kind) -> statedProps.put(name, nullable(measured(kind))));
        Map<String, Object> stated = nullable(obj(statedProps, List.of()));
        stated.put("description", "Values written in the source keyed by field name, each with its own unit; "
                + "null when absent");
        return obj(
                props("geometryUnit", Map.of("type", "string", "enum", MeasureUnits.LENGTH_UNITS),
                        "geometryUnitSource", Map.of("type", "string", "enum", MeasureUnits.UNIT_SOURCES),
                        "vertices", array(vertex),
                        "walls", array(wall),
                        "ceilingHeight", nullable(measured(Kind.LENGTH)), "internalCorners", nullable(integer()),
                        "narrowPlace", nullable(measured(Kind.LENGTH)), "stated", stated,
                        "scaleEstablished", Map.of("type", "boolean"), "confidence", num()),
                List.of("geometryUnit", "geometryUnitSource", "vertices", "walls", "confidence"));
    }

    /**
     * Schema of a {@link Measured}: {@code { value, unit, unitSource }} with the unit enum matching the
     * kind of the field (counts carry no unit).
     */
    private static Map<String, Object> measured(Kind kind) {
        if (kind == Kind.COUNT) {
            return obj(props("value", num()), List.of("value"));
        }
        List<String> units = kind == Kind.AREA ? MeasureUnits.AREA_UNITS : MeasureUnits.LENGTH_UNITS;
        return obj(
                props("value", num(),
                        "unit", Map.of("type", "string", "enum", units),
                        "unitSource", Map.of("type", "string", "enum", MeasureUnits.UNIT_SOURCES)),
                List.of("value", "unit", "unitSource"));
    }

    private static Map<String, Object> obj(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        if (!required.isEmpty()) {
            m.put("required", required);
        }
        return m;
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private static Map<String, Object> str() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> num() {
        return Map.of("type", "number");
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "integer");
    }

    private static Map<String, Object> nullable(Map<String, Object> base) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.put("nullable", true);
        return m;
    }
}
