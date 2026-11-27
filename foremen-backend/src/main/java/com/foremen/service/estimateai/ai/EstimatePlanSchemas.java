package com.foremen.service.estimateai.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FOR-05-04 (Requirements 5.2, 21.1) — JSON schema of the planner answer. Every {@code volumes[]} entry
 * is one cell {@code (work, room)} and carries a required {@code rationale}; {@code value} and
 * {@code volumeReason} are optional (the engine computes the volume unless the drawings differ).
 */
public final class EstimatePlanSchemas {

    private EstimatePlanSchemas() {
    }

    public static Map<String, Object> response() {
        Map<String, Object> source = obj(props(
                "file", str("Source file name"),
                "page", num("Page number")), List.of());
        Map<String, Object> note = obj(props(
                "text", str("At most 500 characters"),
                "workCode", str("Related catalog work code, if any"),
                "roomId", num("Related room id, if any"),
                "source", source), List.of("text"));
        Map<String, Object> cell = obj(props(
                "roomId", num("Saved room id"),
                "value", num("Volume only when the drawings differ from the room dimensions"),
                "unit", str("Unit of the volume"),
                "basis", str("Where the volume comes from"),
                "rationale", str("Why this work applies in this room, at most 500 characters"),
                "volumeReason", str("Why the formula volume is not enough; give it with value")),
                List.of("roomId", "rationale"));
        Map<String, Object> work = obj(props(
                "workCode", str("Catalog code of the work"),
                "roomIds", array(num("Saved room id")),
                "volumes", array(cell),
                "status", enumStr("CONFIRMED", "ASSUMED", "QUESTION"),
                "confidence", num("0..1"),
                "source", source,
                "alternativeGroup", str("Works of the same group are exclusive")),
                List.of("workCode", "roomIds", "volumes", "status"));
        Map<String, Object> unpriced = obj(props(
                "name", str("Needed work"),
                "unit", str("Unit"),
                "estimatedVolume", num("Estimated volume"),
                "reason", str("Why it is not in the catalog"),
                "workCode", str("Catalog code when known")), List.of("name"));
        Map<String, Object> coefficient = obj(props(
                "scope", enumStr("PROJECT", "WORK_CATEGORY", "WORK_ITEM"),
                "targetCode", str("Category or work code for a narrower scope"),
                "pct", num("Signed percent; stated by the user only")), List.of("scope", "pct"));
        return obj(props(
                "works", array(work),
                "assumptions", array(note),
                "questions", array(note),
                "unpriced", array(unpriced),
                "coefficients", array(coefficient)), List.of("works"));
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

    private static Map<String, Object> array(Map<String, Object> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> str(String description) {
        return typed("string", description);
    }

    private static Map<String, Object> num(String description) {
        return typed("number", description);
    }

    private static Map<String, Object> typed(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    private static Map<String, Object> enumStr(String... values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("enum", List.of(values));
        return m;
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
