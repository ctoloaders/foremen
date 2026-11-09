package com.foremen.service.dashboard.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.dashboard.DashboardAiProperties;
import com.foremen.service.dashboard.GridMath;
import com.foremen.service.dashboard.ai.WidgetDraftException.Reason;
import com.foremen.service.dashboard.config.WidgetConfig.Localized;
import com.foremen.service.dashboard.config.WidgetConfigValidator;
import com.foremen.service.dashboard.config.WidgetConfigValidator.Violation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * FOR-10 (Requirements 13.3, 13.5, 13.6) — Gemini-backed {@link WidgetDraftClient}.
 *
 * <p>Each call is a structured-output request. The answer is parsed, checked structurally and its
 * Widget_Config is run through {@link WidgetConfigValidator}; an invalid answer is retried exactly once
 * (bounded) with the violations appended to the prompt, then fails with
 * {@link Reason#INVALID_RESPONSE}. Provider failures are not retried. Every call has a timeout. INFO
 * logs carry only sizes and durations — never the prompt or the model output.
 */
@Slf4j
@Component
@ConditionalOnExpression("${foremen.dashboard.ai.enabled:false} and !${foremen.dashboard.ai.llm.stub:false}")
public class GeminiWidgetDraftClient implements WidgetDraftClient {

    /** Default per-call timeout. */
    public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(90);
    /** One initial attempt plus one corrective retry. */
    static final int MAX_ATTEMPTS = 2;

    private final WidgetDraftTransport transport;
    private final WidgetConfigValidator validator;
    private final Duration callTimeout;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GeminiWidgetDraftClient(DashboardAiProperties properties, WidgetConfigValidator validator) {
        this(new GenAiWidgetDraftTransport(properties), validator, DEFAULT_CALL_TIMEOUT);
    }

    GeminiWidgetDraftClient(WidgetDraftTransport transport, WidgetConfigValidator validator,
                            Duration callTimeout) {
        this.transport = transport;
        this.validator = validator;
        this.callTimeout = callTimeout;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public DraftResponse draft(DraftRequest request) {
        String user = WidgetDraftPrompts.user(request);
        Map<String, Object> schema = WidgetDraftSchemas.draft(request.catalog(), request.routes());
        String prompt = user;
        String problem = null;
        List<Violation> violations = List.of();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long started = System.nanoTime();
            String json = transport.generate(WidgetDraftPrompts.SYSTEM, prompt, schema, callTimeout);
            long ms = (System.nanoTime() - started) / 1_000_000;
            DraftResponse parsed = null;
            violations = List.of();
            if (json == null || json.isBlank()) {
                problem = "empty answer";
            } else {
                try {
                    JsonNode root = mapper.readTree(stripFence(json));
                    problem = structuralProblem(root);
                    if (problem == null) {
                        parsed = toDraft(root);
                        violations = validator.validate(parsed.config(), parsed.singleProject()).violations();
                        if (!violations.isEmpty()) {
                            problem = "the config has " + violations.size() + " validation violation(s)";
                        }
                    }
                } catch (JsonProcessingException e) {
                    problem = "malformed JSON";
                }
            }
            // Sizes and timings only — never the prompt or the model output (13.5).
            log.info("dashboard-ai draft attempt={} ms={} promptChars={} answerChars={} violations={} valid={}",
                    attempt, ms, prompt.length(), json == null ? 0 : json.length(), violations.size(),
                    problem == null);
            if (problem == null) {
                return parsed;
            }
            prompt = user + WidgetDraftPrompts.retrySuffix(problem, violations);
        }
        throw new WidgetDraftException(Reason.INVALID_RESPONSE,
                "Gemini draft was invalid after " + MAX_ATTEMPTS + " attempts: " + problem, violations, null);
    }

    /** Null when the envelope is well-formed; otherwise a short description of what is wrong. */
    private static String structuralProblem(JsonNode root) {
        if (root == null || !root.isObject()) {
            return "the answer is not a JSON object";
        }
        if (!root.path("config").isObject()) {
            return "config is missing";
        }
        if (!isLocalized(root.path("names"))) {
            return "names must have non-blank ru and pl";
        }
        if (!isLocalized(root.path("descriptions")) || !isLocalized(root.path("explanation"))) {
            return "descriptions and explanation must have non-blank ru and pl";
        }
        if (!inRange(root.path("colSpan"), GridMath.COLS) || !inRange(root.path("rowSpan"), GridMath.ROWS)) {
            return "colSpan must be 1.." + GridMath.COLS + " and rowSpan 1.." + GridMath.ROWS;
        }
        String scope = root.path("projectScope").asText("");
        if (!DraftResponse.SCOPE_NONE.equals(scope) && !DraftResponse.SCOPE_SINGLE_PROJECT.equals(scope)) {
            return "projectScope must be NONE or SINGLE_PROJECT";
        }
        return null;
    }

    private static boolean isLocalized(JsonNode n) {
        return n.isObject() && !n.path("ru").asText("").isBlank() && !n.path("pl").asText("").isBlank();
    }

    private static boolean inRange(JsonNode n, int max) {
        return n.isInt() && n.asInt() >= 1 && n.asInt() <= max;
    }

    private static DraftResponse toDraft(JsonNode root) {
        return new DraftResponse(localized(root.get("names")), localized(root.get("descriptions")),
                root.get("colSpan").asInt(), root.get("rowSpan").asInt(), root.get("projectScope").asText(),
                root.get("config"), localized(root.get("explanation")));
    }

    private static Localized localized(JsonNode n) {
        return new Localized(n.get("ru").asText().strip(), n.get("pl").asText().strip());
    }

    /** Tolerate a markdown code fence around the JSON. */
    static String stripFence(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                return t.substring(nl + 1, end).strip();
            }
        }
        return t;
    }
}
