package com.foremen.service.roomsimport.ai;

import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.service.ai.GeminiClientFactory;
import com.foremen.service.roomsimport.extract.PageImage;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link GeminiJsonTransport} over the google-genai SDK with structured (JSON schema) output. The
 * client is built lazily on first use so a credential problem surfaces as a handled job failure
 * rather than a startup failure.
 */
public class GenAiJsonTransport implements GeminiJsonTransport {

    private final RoomsImportProperties properties;
    private volatile Client client;
    private final Object lock = new Object();

    public GenAiJsonTransport(RoomsImportProperties properties) {
        this.properties = properties;
    }

    @Override
    public String generate(String systemInstruction, String userText, List<PageImage> images,
                           Map<String, Object> responseSchema, Duration timeout) {
        String model = properties.llm().model();
        if (model == null || model.isBlank()) {
            throw new RoomsVisionException(RoomsVisionException.Reason.DISABLED,
                    "foremen.rooms-import.llm.model is not configured");
        }
        try {
            List<Part> parts = new ArrayList<>();
            parts.add(Part.fromText(userText));
            for (PageImage image : images) {
                parts.add(Part.fromBytes(image.bytes(), image.mimeType()));
            }
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                    .responseMimeType("application/json")
                    .responseJsonSchema(responseSchema)
                    .temperature(0f)
                    .httpOptions(HttpOptions.builder().timeout((int) timeout.toMillis()).build())
                    .build();
            Content content = Content.builder().role("user").parts(parts).build();
            GenerateContentResponse response = client().models.generateContent(model.strip(), content, config);
            String text = response.text();
            return text == null ? "" : text;
        } catch (RoomsVisionException e) {
            throw e;
        } catch (RuntimeException e) {
            boolean timeoutLike = e.getCause() instanceof java.net.SocketTimeoutException
                    || e.getCause() instanceof java.net.http.HttpTimeoutException
                    || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timed out"));
            throw new RoomsVisionException(
                    timeoutLike ? RoomsVisionException.Reason.TIMEOUT : RoomsVisionException.Reason.LLM_UNAVAILABLE,
                    "Gemini call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private Client client() {
        Client c = client;
        if (c == null) {
            synchronized (lock) {
                c = client;
                if (c == null) {
                    RoomsImportProperties.Llm llm = properties.llm();
                    c = GeminiClientFactory.build(llm.apiKey(), llm.project(), llm.location(),
                            llm.credentials().path(), llm.credentials().location());
                    client = c;
                }
            }
        }
        return c;
    }
}
