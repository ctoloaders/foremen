package com.foremen.service.estimateai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.service.estimateai.chat.ToolOutput;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-04 rework (Requirements 24.1, 24.7; design R3) — the {@code assign_work} function declaration of the
 * job and its argument handling. It is not part of the chat registry. A rejected call is returned to the
 * model as an error result ({@link ToolOutput#error}); the caller counts it and continues.
 *
 * <p>Price coefficients are never read from the model arguments: only coefficients stated by the user
 * (passed by the worker) can change a price (Requirement 6.4).
 */
@Component
@RequiredArgsConstructor
public class AssignWorkTool {

    public static final String NAME = "assign_work";
    /** Result code for arguments that cannot be parsed. */
    public static final String ERR_ARGS = "error.estimateai.tool.args";

    private final AssignWorkService service;
    /** The application has no shared Jackson 2 bean (Spring Boot 4), so the tool owns a plain mapper. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** The function declaration {@code {name, description, parameters}} given to the model. */
    public JsonNode declaration() {
        ObjectNode d = mapper.createObjectNode();
        d.put("name", NAME);
        d.put("description", "Applies one work from the catalog to one room or to a listed set of rooms of the "
                + "project and writes it into the estimate right away. Call it once per assignment. "
                + "`volume` is the volume per listed room; omit it to use the formula volume.");
        ObjectNode s = d.putObject("parameters");
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        prop(p, "workItemId", "integer", "Id of the catalog work.");
        ObjectNode rooms = prop(p, "roomIds", "array", "Ids of the project rooms the work applies to.");
        rooms.putObject("items").put("type", "integer");
        prop(p, "volume", "number", "Model volume for each listed room, in the work's unit. Optional.");
        prop(p, "volumeReason", "string", "Why the volume differs from the formula. Required with a volume.");
        prop(p, "rationale", "string", "Mandatory. Why this work applies to the room(s).");
        prop(p, "status", "string", "CONFIRMED, ASSUMED or QUESTION.");
        prop(p, "confidence", "number", "Confidence from 0 to 1.");
        prop(p, "source", "string", "Evidence, for example \"plan.pdf, p.3\".");
        prop(p, "priceReason", "string", "Reason of a price coefficient the user stated. Optional.");
        ArrayNode req = s.putArray("required");
        req.add("workItemId").add("roomIds").add("rationale").add("status");
        return d;
    }

    /**
     * Runs one model call. Never throws for an expected rejection.
     *
     * @param coefficients coefficients stated by the user that apply to this work, may be empty
     */
    public ToolOutput run(AiJobContext ctx, JsonNode args, List<AssignWorkCommand.Coefficient> coefficients) {
        AssignWorkCommand cmd;
        try {
            cmd = parse(args, coefficients);
        } catch (IllegalArgumentException e) {
            return ToolOutput.error(ERR_ARGS);
        }
        try {
            AssignWorkResult r = service.assign(ctx, cmd);
            ObjectNode data = mapper.createObjectNode();
            data.put("workItemId", r.workItemId());
            data.put("workCode", r.workCode());
            ArrayNode cells = data.putArray("cells");
            for (AssignWorkResult.Cell c : r.cells()) {
                ObjectNode n = cells.addObject();
                n.put("roomId", c.roomId());
                n.put("volume", c.volume());
                n.put("volumeOverridden", c.volumeOverridden());
                n.put("materialLines", c.materialLines());
            }
            return ToolOutput.ok(data);
        } catch (AssignRejected e) {
            return ToolOutput.error(e.code());
        }
    }

    static AssignWorkCommand parse(JsonNode a, List<AssignWorkCommand.Coefficient> coefficients) {
        if (a == null || !a.isObject()) {
            throw new IllegalArgumentException("args");
        }
        List<Long> roomIds = new ArrayList<>();
        JsonNode rooms = a.get("roomIds");
        if (rooms != null && rooms.isArray()) {
            for (JsonNode r : rooms) {
                if (!r.canConvertToLong()) {
                    throw new IllegalArgumentException("roomIds");
                }
                roomIds.add(r.asLong());
            }
        }
        JsonNode w = a.get("workItemId");
        Long workItemId = w != null && w.canConvertToLong() ? w.asLong() : null;
        return new AssignWorkCommand(workItemId, roomIds, decimal(a.get("volume")), text(a, "volumeReason"),
                text(a, "rationale"), text(a, "status"), decimal(a.get("confidence")), text(a, "source"),
                text(a, "priceReason"), coefficients);
    }

    private static ObjectNode prop(ObjectNode props, String name, String type, String description) {
        ObjectNode n = props.putObject(name);
        n.put("type", type);
        n.put("description", description);
        return n;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static BigDecimal decimal(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isNumber()) {
            throw new IllegalArgumentException("number");
        }
        return v.decimalValue();
    }
}
