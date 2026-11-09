package com.foremen.service.dashboard.ai;

import com.foremen.config.dashboard.DashboardAiProperties;
import com.foremen.service.ai.GeminiClientFactory;
import com.foremen.service.dashboard.ai.WidgetDraftException.Reason;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;

import java.time.Duration;
import java.util.Map;

/**
 * {@link WidgetDraftTransport} over the google-genai SDK with structured (JSON schema) output. The
 * client is built lazily so a credential problem surfaces as a handled draft failure, not a startup
 * failure.
 */
public class GenAiWidgetDraftTransport implements WidgetDraftTransport {

    private final DashboardAiProperties properties;
    private volatile Client client;
    private final Object lock = new Object();

    public GenAiWidgetDraftTransport(DashboardAiProperties properties) {
        this.properties = properties;
    }

    @Override
    public String generate(String systemInstruction, String userText, Map<String, Object> responseSchema,
                           Duration timeout) {
        String model = properties.llm().model();
        if (model == null || model.isBlank()) {
            throw new WidgetDraftException(Reason.DISABLED, "foremen.dashboard.ai.llm.model is not configured");
        }
        try {
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                    .responseMimeType("application/json")
                    .responseJsonSchema(responseSchema)
                    .temperature(0f)
                    .httpOptions(HttpOptions.builder().timeout((int) timeout.toMillis()).build())
                    .build();
            Content content = Content.builder().role("user").parts(Part.fromText(userText)).build();
            GenerateContentResponse response = client().models.generateContent(model.strip(), content, config);
            String text = response.text();
            return text == null ? "" : text;
        } catch (WidgetDraftException e) {
            throw e;
        } catch (RuntimeException e) {
            boolean timeoutLike = e.getCause() instanceof java.net.SocketTimeoutException
                    || e.getCause() instanceof java.net.http.HttpTimeoutException
                    || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timed out"));
            throw new WidgetDraftException(timeoutLike ? Reason.TIMEOUT : Reason.LLM_UNAVAILABLE,
                    "Gemini call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private Client client() {
        Client c = client;
        if (c == null) {
            synchronized (lock) {
                c = client;
                if (c == null) {
                    DashboardAiProperties.Llm llm = properties.llm();
                    c = GeminiClientFactory.build(llm.apiKey(), llm.project(), llm.location(),
                            llm.credentials().path(), llm.credentials().location());
                    client = c;
                }
            }
        }
        return c;
    }
}
