package com.foremen.service.estimateai.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.service.estimateai.ai.OfferChatClient;
import com.foremen.service.permission.ForemenPermissionEvaluator;

/**
 * FOR-05-04 (design B8, Requirements 11.1 to 11.4) — the single place that defines the fixed chat toolset.
 * A tool exists for the model only if it is registered here with a name, a JSON schema and the permission
 * it needs; adding one requires extending {@link OfferChatTools#create}, which the registry test pins to
 * the allowed list.
 *
 * <p>{@link #execute} checks the caller's permission through {@link ForemenPermissionEvaluator} (the same
 * ABAC check as the controllers; ADMIN bypass included) before the tool runs. Every failure (unknown tool,
 * permission, validation) is returned as a tool result, never thrown.</p>
 */
@Component
public class OfferChatToolRegistry {

    /** The allowed toolset, in declaration order. */
    public static final List<String> TOOL_NAMES = List.of("find_work", "get_offer_state", "assign_work",
            "unassign_work", "set_volume", "clear_volume_override", "adjust_price", "set_line_comment");

    static final String ERR_UNKNOWN_TOOL = "error.estimateai.tool.unknown_tool";
    static final String ERR_FORBIDDEN = "error.estimateai.tool.forbidden";

    private final Map<String, OfferChatTool> tools = new LinkedHashMap<>();
    private final ForemenPermissionEvaluator evaluator;
    private final ObjectMapper mapper;

    /** Spring constructor: the application has no Jackson 2 {@link ObjectMapper} bean (Boot 4 wires Jackson 3). */
    @org.springframework.beans.factory.annotation.Autowired
    public OfferChatToolRegistry(ChatCatalog catalog, ForemenPermissionEvaluator evaluator) {
        this(catalog, evaluator, new ObjectMapper().findAndRegisterModules());
    }

    public OfferChatToolRegistry(ChatCatalog catalog, ForemenPermissionEvaluator evaluator, ObjectMapper mapper) {
        this.evaluator = evaluator;
        this.mapper = mapper;
        for (OfferChatTool t : OfferChatTools.create(catalog, mapper)) {
            tools.put(t.name(), t);
        }
    }

    public List<String> toolNames() {
        return List.copyOf(tools.keySet());
    }

    public OfferChatTool tool(String name) {
        return tools.get(name);
    }

    /** Function declarations for the model: an array of {@code {name, description, parameters}}. */
    public JsonNode declarations() {
        ArrayNode out = mapper.createArrayNode();
        for (OfferChatTool t : tools.values()) {
            ObjectNode d = out.addObject();
            d.put("name", t.name());
            d.put("description", t.description());
            d.set("parameters", t.params());
        }
        return out;
    }

    /** Runs one model tool call under the caller's identity and returns the result for the model. */
    public OfferChatClient.ToolResult execute(ToolContext ctx, OfferChatClient.ToolCall call) {
        return new OfferChatClient.ToolResult(call.name(), run(ctx, call.name(), call.args()).toJson(mapper));
    }

    /** Same as {@link #execute} but returns the typed output. */
    public ToolOutput run(ToolContext ctx, String name, JsonNode args) {
        OfferChatTool tool = tools.get(name);
        if (tool == null) {
            return ToolOutput.error(ERR_UNKNOWN_TOOL);
        }
        for (ToolPermission.Required r : tool.permission().required()) {
            if (ctx.roleCode() == null || !evaluator.isAllowed(ctx.roleCode(), r.resource(), r.operation())) {
                return ToolOutput.error(ERR_FORBIDDEN);
            }
        }
        JsonNode a = args == null || args.isNull() ? mapper.createObjectNode() : args;
        try {
            return tool.run(ctx, a);
        } catch (RuntimeException e) {
            return ToolOutput.error(OfferChatTools.ERR_ARGS);
        }
    }
}
