package com.foremen.service.estimateai.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * FOR-05-04 (Requirement 11.4) — the result of one tool run, returned to the model. A validation failure
 * is a result with {@code error} set (never an HTTP error), so the model can correct itself.
 */
public record ToolOutput(boolean ok, String error, JsonNode data) {

    public static ToolOutput ok(JsonNode data) {
        return new ToolOutput(true, null, data);
    }

    public static ToolOutput error(String code) {
        return new ToolOutput(false, code, null);
    }

    /** The JSON the model receives: {@code {"ok":true, ...data}} or {@code {"ok":false,"error":"code"}}. */
    public JsonNode toJson(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("ok", ok);
        if (!ok) {
            n.put("error", error);
        } else if (data != null && data.isObject()) {
            n.setAll((ObjectNode) data);
        } else if (data != null) {
            n.set("data", data);
        }
        return n;
    }
}
