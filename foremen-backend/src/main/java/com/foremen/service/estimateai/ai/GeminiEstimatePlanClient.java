package com.foremen.service.estimateai.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.estimateai.EstimateAiProperties;
import com.foremen.service.estimateai.ai.EstimateAiException.Reason;

/**
 * FOR-05-04 (Requirements 5.2, 7.5) — Gemini-backed {@link EstimatePlanClient}, active when
 * {@code foremen.estimate-ai.enabled=true} and {@code foremen.estimate-ai.llm.stub=false}. One call per
 * request with the structured schema of {@link EstimatePlanSchemas}; an unparsable body is reported as
 * {@link Reason#INVALID_RESPONSE} and retried by the planner.
 */
@Component
@ConditionalOnExpression("${foremen.estimate-ai.enabled:false} and !${foremen.estimate-ai.llm.stub:false}")
public class GeminiEstimatePlanClient implements EstimatePlanClient {

    private final PlanJsonTransport transport;
    private final EstimateAiProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GeminiEstimatePlanClient(EstimateAiProperties properties) {
        this(new GenAiPlanJsonTransport(properties), properties);
    }

    GeminiEstimatePlanClient(PlanJsonTransport transport, EstimateAiProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public PlanResponse plan(PlanRequest request) {
        String body = transport.generate(request.systemInstruction(), userText(request),
                EstimatePlanSchemas.response(), properties.llm().timeout(), request.attachments());
        JsonNode node;
        try {
            node = mapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new EstimateAiException(Reason.INVALID_RESPONSE, "Plan answer is not valid JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new EstimateAiException(Reason.INVALID_RESPONSE, "Plan answer is not a JSON object");
        }
        return new PlanResponse(node);
    }

    static String userText(PlanRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Saved rooms\n").append(nz(r.roomsText())).append("\n\n");
        sb.append("## Catalog\n").append(nz(r.catalogText())).append("\n\n");
        sb.append(nz(r.userInstruction()));
        if (r.reaskText() != null && !r.reaskText().isBlank()) {
            sb.append("\n\n## Follow-up\n").append(r.reaskText());
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
