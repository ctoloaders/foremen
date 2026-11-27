package com.foremen.service.estimateai.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.foremen.config.estimateai.EstimateAiProperties;
import com.foremen.service.estimateai.ai.EstimateAiException.Reason;
import com.foremen.service.estimateai.ai.EstimatePlanClient.PlanRequest;

/** FOR-05-04 task 3.5 — Gemini-style client over a fake transport; schema requires per-cell rationale. */
class GeminiEstimatePlanClientTest {

    private static final EstimateAiProperties PROPS = new EstimateAiProperties(true, null, 0, 0, null, null, null);

    private static PlanRequest request(String reask) {
        return new PlanRequest("sys", "<user_instruction>\nu\n</user_instruction>", "CATALOG", "ROOMS",
                List.of(1L), List.of("1.01"), reask);
    }

    @Test
    void parsesJsonAndComposesUserText() {
        String[] captured = new String[1];
        GeminiEstimatePlanClient client = new GeminiEstimatePlanClient((sys, user, schema, timeout) -> {
            captured[0] = user;
            return "{\"works\":[]}";
        }, PROPS);

        assertThat(client.plan(request("ask rationale")).plan().get("works").isArray()).isTrue();
        assertThat(captured[0]).contains("ROOMS", "CATALOG", "<user_instruction>", "ask rationale");
    }

    @Test
    void unparsableBodyIsInvalidResponse() {
        GeminiEstimatePlanClient client = new GeminiEstimatePlanClient((s, u, sc, t) -> "not json", PROPS);

        assertThatThrownBy(() -> client.plan(request(null))).isInstanceOfSatisfying(EstimateAiException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaRequiresRationalePerCellAndKeepsVolumeReasonOptional() {
        Map<String, Object> schema = EstimatePlanSchemas.response();
        Map<String, Object> works = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("works");
        Map<String, Object> work = (Map<String, Object>) works.get("items");
        Map<String, Object> volumes = (Map<String, Object>) ((Map<String, Object>) work.get("properties")).get("volumes");
        Map<String, Object> cell = (Map<String, Object>) volumes.get("items");

        assertThat((List<String>) cell.get("required")).contains("roomId", "rationale").doesNotContain("volumeReason");
        assertThat((Map<String, Object>) cell.get("properties")).containsKey("volumeReason");
    }
}
