package com.foremen.service.roomsimport.ai;

import com.foremen.service.roomsimport.extract.PageImage;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The raw "send prompt + images, get JSON text back" seam under {@link GeminiRoomsVisionClient}.
 * Exists so response validation/retry can be unit-tested with a fake. Implementations throw
 * {@link RoomsVisionException} ({@code LLM_UNAVAILABLE} / {@code TIMEOUT}) on provider failure.
 */
@FunctionalInterface
public interface GeminiJsonTransport {

    /**
     * @param systemInstruction system prompt
     * @param userText          user prompt text
     * @param images            images attached after the text (may be empty)
     * @param responseSchema    JSON schema of the expected answer
     * @param timeout           per-call timeout
     * @return the model's JSON text (possibly blank/invalid; the caller validates)
     */
    String generate(String systemInstruction, String userText, List<PageImage> images,
                    Map<String, Object> responseSchema, Duration timeout);
}
