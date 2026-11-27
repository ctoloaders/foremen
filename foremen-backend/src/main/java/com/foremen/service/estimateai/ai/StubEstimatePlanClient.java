package com.foremen.service.estimateai.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.service.estimateai.ai.EstimateAiException.Reason;

/**
 * FOR-05-04 (Requirement 7.5) — deterministic {@link EstimatePlanClient} for tests and the Docker
 * stack, active when {@code foremen.estimate-ai.enabled=true} and {@code foremen.estimate-ai.llm.stub=true}.
 *
 * <p>Answers with the first catalog work applied to every saved room, with a fixed rationale, so the
 * same input always yields the same plan. Markers in the user instruction: {@code STUB:FAIL} simulates
 * an outage ({@link Reason#LLM_UNAVAILABLE}); {@code STUB:INVALID} returns a body without {@code works};
 * {@code STUB:EMPTY} returns a plan without works.
 */
@Component
@ConditionalOnExpression("${foremen.estimate-ai.enabled:false} and ${foremen.estimate-ai.llm.stub:false}")
public class StubEstimatePlanClient implements EstimatePlanClient {

    public static final String FAIL_MARKER = "STUB:FAIL";
    public static final String INVALID_MARKER = "STUB:INVALID";
    public static final String EMPTY_MARKER = "STUB:EMPTY";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public PlanResponse plan(PlanRequest request) {
        String prompt = request.userInstruction() == null ? "" : request.userInstruction();
        if (prompt.contains(FAIL_MARKER)) {
            throw new EstimateAiException(Reason.LLM_UNAVAILABLE, "stub: LLM is down");
        }
        ObjectNode root = mapper.createObjectNode();
        if (prompt.contains(INVALID_MARKER)) {
            root.put("unexpected", true);
            return new PlanResponse(root);
        }
        ArrayNode works = root.putArray("works");
        if (!prompt.contains(EMPTY_MARKER) && !request.workCodes().isEmpty() && !request.roomIds().isEmpty()) {
            ObjectNode w = works.addObject();
            w.put("workCode", request.workCodes().get(0));
            ArrayNode rooms = w.putArray("roomIds");
            request.roomIds().forEach(rooms::add);
            w.putArray("volumes");
            w.put("status", "ASSUMED");
            w.put("confidence", 0.8);
            ObjectNode source = w.putObject("source");
            source.put("file", "stub.pdf");
            source.put("page", 1);
            w.put("rationale", "Stub plan: first catalog work applied to every room.");
        }
        root.putArray("assumptions").addObject().put("text", "Stub assumption");
        root.putArray("questions");
        root.putArray("unpriced");
        root.putArray("coefficients");
        return new PlanResponse(root);
    }
}
