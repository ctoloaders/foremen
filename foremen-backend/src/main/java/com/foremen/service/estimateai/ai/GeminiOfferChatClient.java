package com.foremen.service.estimateai.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.estimateai.EstimateAiProperties;
import com.foremen.service.ai.GeminiClientFactory;
import com.foremen.service.estimateai.ai.EstimateAiException.Reason;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;

/**
 * FOR-05-04 (Requirements 10.4, 11.1, 7.5) — Gemini-backed {@link OfferChatClient} with function calling,
 * active when {@code foremen.estimate-ai.enabled=true} and {@code foremen.estimate-ai.llm.stub=false}.
 *
 * <p>Each call is stateless: the history, the offer text, the user message and the tool results of the
 * turn so far are rendered into the contents, and the tool declarations of the registry are sent as
 * function declarations (no automatic function calling). The service executes the requested tools and
 * calls again with the results. Failures map to {@link EstimateAiException} like the plan client.</p>
 */
@Component
@ConditionalOnExpression("${foremen.estimate-ai.enabled:false} and !${foremen.estimate-ai.llm.stub:false}")
public class GeminiOfferChatClient implements OfferChatClient {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GeminiOfferChatClient.class);

    private final EstimateAiProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Client client;
    private final Object lock = new Object();

    @Autowired
    public GeminiOfferChatClient(EstimateAiProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        EstimateAiProperties.Llm llm = properties.llm();
        String model = llm.model();
        if (model == null || model.isBlank()) {
            throw new EstimateAiException(Reason.DISABLED, "foremen.estimate-ai.llm.model is not configured");
        }
        try {
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(Part.fromText(nz(request.systemInstruction()))))
                    .tools(List.of(Tool.builder().functionDeclarations(declarations(request.toolDeclarations())).build()))
                    .temperature(llm.temperature().floatValue())
                    .httpOptions(HttpOptions.builder().timeout((int) llm.timeout().toMillis()).build())
                    .build();
            GenerateContentResponse response = client().models.generateContent(model.strip(), contents(request), config);
            List<ToolCall> calls = new ArrayList<>();
            List<FunctionCall> functionCalls = response.functionCalls();
            if (functionCalls != null) {
                for (FunctionCall fc : functionCalls) {
                    String name = fc.name().orElse(null);
                    if (name == null) {
                        continue;
                    }
                    Map<String, Object> args = fc.args().orElse(Map.of());
                    calls.add(new ToolCall(name, mapper.valueToTree(args)));
                }
            }
            if (!calls.isEmpty()) {
                return new ChatResponse("", calls);
            }
            String text = response.text();
            return new ChatResponse(text == null ? "" : text, List.of());
        } catch (EstimateAiException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Gemini chat call failed: {}", e.getClass().getSimpleName());
            boolean timeoutLike = e.getCause() instanceof java.net.SocketTimeoutException
                    || e.getCause() instanceof java.net.http.HttpTimeoutException
                    || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timed out"));
            throw new EstimateAiException(timeoutLike ? Reason.TIMEOUT : Reason.LLM_UNAVAILABLE,
                    "Gemini chat call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private List<Content> contents(ChatRequest r) {
        List<Content> out = new ArrayList<>();
        for (ChatTurn t : r.history()) {
            if (t.content() == null || t.content().isBlank()) {
                continue;
            }
            String role = "ASSISTANT".equalsIgnoreCase(t.role()) ? "model" : "user";
            out.add(Content.builder().role(role).parts(List.of(Part.fromText(t.content()))).build());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Current offer\n").append(nz(r.snapshotText())).append("\n\n");
        sb.append("## User message\n").append(nz(r.userMessage()));
        if (!r.toolResults().isEmpty()) {
            sb.append("\n\n## Tool results so far in this turn\n");
            for (ToolResult tr : r.toolResults()) {
                sb.append("- ").append(tr.name()).append(": ").append(tr.result()).append('\n');
            }
            sb.append("Continue: call more tools if needed, otherwise answer the user.");
        }
        out.add(Content.builder().role("user").parts(List.of(Part.fromText(sb.toString()))).build());
        return out;
    }

    private List<FunctionDeclaration> declarations(JsonNode decls) {
        List<FunctionDeclaration> out = new ArrayList<>();
        if (decls == null || !decls.isArray()) {
            return out;
        }
        for (JsonNode d : decls) {
            out.add(FunctionDeclaration.builder()
                    .name(d.path("name").asText())
                    .description(d.path("description").asText(""))
                    .parametersJsonSchema(mapper.convertValue(d.path("parameters"), Map.class))
                    .build());
        }
        return out;
    }

    private Client client() {
        Client c = client;
        if (c == null) {
            synchronized (lock) {
                c = client;
                if (c == null) {
                    EstimateAiProperties.Llm llm = properties.llm();
                    c = GeminiClientFactory.build(llm.apiKey(), llm.project(), llm.location(),
                            llm.credentials().path(), llm.credentials().location());
                    client = c;
                }
            }
        }
        return c;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
