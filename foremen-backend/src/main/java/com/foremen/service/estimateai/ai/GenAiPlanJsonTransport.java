package com.foremen.service.estimateai.ai;

import java.time.Duration;
import java.util.Map;

import com.foremen.config.estimateai.EstimateAiProperties;
import com.foremen.service.ai.GeminiClientFactory;
import com.foremen.service.estimateai.ai.EstimateAiException.Reason;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;

/**
 * FOR-05-04 — {@link PlanJsonTransport} over the google-genai SDK with structured (JSON schema) output.
 * The client is built lazily so a credential problem surfaces as a handled job failure.
 */
public class GenAiPlanJsonTransport implements PlanJsonTransport {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GenAiPlanJsonTransport.class);

    private final EstimateAiProperties properties;
    private volatile Client client;
    private final Object lock = new Object();

    public GenAiPlanJsonTransport(EstimateAiProperties properties) {
        this.properties = properties;
    }

    @Override
    public String generate(String systemInstruction, String userText, Map<String, Object> responseSchema,
                           Duration timeout) {
        return generate(systemInstruction, userText, responseSchema, timeout, java.util.List.of());
    }

    @Override
    public String generate(String systemInstruction, String userText, Map<String, Object> responseSchema,
                           Duration timeout, java.util.List<EstimatePlanClient.Attachment> attachments) {
        EstimateAiProperties.Llm llm = properties.llm();
        String model = llm.model();
        if (model == null || model.isBlank()) {
            throw new EstimateAiException(Reason.DISABLED, "foremen.estimate-ai.llm.model is not configured");
        }
        try {
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                    .responseMimeType("application/json")
                    .responseJsonSchema(responseSchema)
                    .temperature(llm.temperature().floatValue())
                    .httpOptions(HttpOptions.builder().timeout((int) timeout.toMillis()).build())
                    .build();
            java.util.List<Part> parts = new java.util.ArrayList<>();
            for (EstimatePlanClient.Attachment a : attachments == null
                    ? java.util.List.<EstimatePlanClient.Attachment>of() : attachments) {
                if (a != null && a.data() != null && a.data().length > 0 && a.mimeType() != null) {
                    parts.add(Part.fromBytes(a.data(), a.mimeType()));
                }
            }
            parts.add(Part.fromText(userText));
            Content content = Content.builder().role("user").parts(parts).build();
            GenerateContentResponse response = client().models.generateContent(model.strip(), content, config);
            String text = response.text();
            return text == null ? "" : text;
        } catch (EstimateAiException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Gemini call failed: {}", e.getClass().getSimpleName());
            boolean timeoutLike = e.getCause() instanceof java.net.SocketTimeoutException
                    || e.getCause() instanceof java.net.http.HttpTimeoutException
                    || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timed out"));
            throw new EstimateAiException(timeoutLike ? Reason.TIMEOUT : Reason.LLM_UNAVAILABLE,
                    "Gemini call failed: " + e.getClass().getSimpleName(), e);
        }
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
}
