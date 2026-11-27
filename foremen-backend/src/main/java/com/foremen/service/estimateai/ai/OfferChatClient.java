package com.foremen.service.estimateai.ai;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * FOR-05-04 (design B8) — the model boundary of one chat round with function calling. The real
 * Gemini implementation is added with the chat service (tasks 4.1/4.2); this task provides the
 * {@code Disabled*} and {@code Stub*} fallbacks.
 */
public interface OfferChatClient {

    boolean isEnabled();

    ChatResponse chat(ChatRequest request);

    /**
     * @param systemInstruction the versioned chat system prompt
     * @param snapshotText      compact text rendering of the offer snapshot
     * @param history           the last turns of the conversation
     * @param userMessage       the user's message of this turn
     * @param toolResults       results of the tool calls of the previous round of this turn (empty in round 0)
     * @param toolDeclarations  function declarations of the tool registry
     */
    record ChatRequest(String systemInstruction, String snapshotText, List<ChatTurn> history,
                       String userMessage, List<ToolResult> toolResults, JsonNode toolDeclarations) {
        public ChatRequest {
            history = history == null ? List.of() : List.copyOf(history);
            toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        }
    }

    /** @param role {@code USER} or {@code ASSISTANT} */
    record ChatTurn(String role, String content) {
    }

    record ToolCall(String name, JsonNode args) {
    }

    record ToolResult(String name, JsonNode result) {
    }

    /** @param text final text (blank while {@code toolCalls} is non-empty); {@code toolCalls} requested by the model */
    record ChatResponse(String text, List<ToolCall> toolCalls) {
        public ChatResponse {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }
}
