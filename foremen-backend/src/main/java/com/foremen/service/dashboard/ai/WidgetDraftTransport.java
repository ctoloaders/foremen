package com.foremen.service.dashboard.ai;

import java.time.Duration;
import java.util.Map;

/**
 * The raw "send prompt, get JSON text back" seam under {@link GeminiWidgetDraftClient}; text only, no
 * images. Exists so validation/retry can be unit-tested with a fake. Implementations throw
 * {@link WidgetDraftException} ({@code LLM_UNAVAILABLE} / {@code TIMEOUT} / {@code DISABLED}).
 */
@FunctionalInterface
public interface WidgetDraftTransport {

    /**
     * @param systemInstruction system prompt
     * @param userText          user prompt text
     * @param responseSchema    JSON schema of the expected answer
     * @param timeout           per-call timeout
     * @return the model's JSON text (possibly blank/invalid; the caller validates)
     */
    String generate(String systemInstruction, String userText, Map<String, Object> responseSchema,
                    Duration timeout);
}
