package com.foremen.service.estimateai.chat;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * FOR-05-04 (design B8) — one chat tool: name, description, JSON schema of the arguments, the permission it
 * needs and the run. Registered only in {@link OfferChatToolRegistry}.
 */
public interface OfferChatTool {

    String name();

    String description();

    /** JSON schema (OpenAPI subset understood by Gemini function declarations) of the arguments object. */
    JsonNode params();

    ToolPermission permission();

    ToolOutput run(ToolContext ctx, JsonNode args);
}
