package com.foremen.service.estimateai.ai;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.estimateai.ai.EstimateAiException.Reason;

/**
 * FOR-05-04 (Requirement 7.5) — deterministic {@link OfferChatClient} for tests and the Docker stack,
 * active when {@code foremen.estimate-ai.enabled=true} and {@code foremen.estimate-ai.llm.stub=true}.
 *
 * <p>Round 0 (no tool results yet): the user message may carry {@code STUB:CALL <tool> <json-args>}
 * and the stub then requests exactly that tool call; without the marker it answers with text only.
 * After tool results arrive (round 1+) it answers with a fixed confirmation text. {@code STUB:FAIL}
 * simulates an outage ({@link Reason#LLM_UNAVAILABLE}).
 */
@Component
@ConditionalOnExpression("${foremen.estimate-ai.enabled:false} and ${foremen.estimate-ai.llm.stub:false}")
public class StubOfferChatClient implements OfferChatClient {

    public static final String FAIL_MARKER = "STUB:FAIL";
    public static final String CALL_MARKER = "STUB:CALL ";
    public static final String DONE_TEXT = "Done (stub).";
    public static final String PLAIN_TEXT = "Stub reply.";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String message = request.userMessage() == null ? "" : request.userMessage();
        if (message.contains(FAIL_MARKER)) {
            throw new EstimateAiException(Reason.LLM_UNAVAILABLE, "stub: LLM is down");
        }
        if (!request.toolResults().isEmpty()) {
            return new ChatResponse(DONE_TEXT, List.of());
        }
        int at = message.indexOf(CALL_MARKER);
        if (at < 0) {
            return new ChatResponse(PLAIN_TEXT, List.of());
        }
        String rest = message.substring(at + CALL_MARKER.length()).strip();
        int space = rest.indexOf(' ');
        String tool = space < 0 ? rest : rest.substring(0, space);
        String json = space < 0 ? "{}" : rest.substring(space + 1).strip();
        JsonNode args;
        try {
            args = mapper.readTree(json.isEmpty() ? "{}" : json);
        } catch (Exception e) {
            throw new EstimateAiException(Reason.INVALID_RESPONSE, "stub: bad tool arguments", e);
        }
        return new ChatResponse("", List.of(new ToolCall(tool, args)));
    }
}
