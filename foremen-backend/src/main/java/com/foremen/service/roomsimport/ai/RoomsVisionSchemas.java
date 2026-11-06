package com.foremen.service.roomsimport.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                        "count", integer(), "height", num(), "width", num(), "offset", nullable(num())),
                List.of("type", "count", "height", "width"));
        Map<String, Object> wall = obj(
                props("wallGap", nullable(num()), "finishGap", nullable(num()),
                        "openings", array(opening)),
                List.of());
        Map<String, Object> stated = Map.of("type", "object", "nullable", true,
                "description", "Values written in the source keyed by field name; null when absent");
        return obj(
                props("unit", Map.of("type", "string", "enum", List.of("m", "cm", "mm")),
                        "vertices", array(vertex),
                        "walls", array(wall),
                        "ceilingHeight", nullable(num()), "internalCorners", nullable(integer()),
                        "narrowPlace", nullable(num()), "stated", stated,
                        "scaleEstablished", Map.of("type", "boolean"), "confidence", num()),
                List.of("unit", "vertices", "walls", "confidence"));
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
