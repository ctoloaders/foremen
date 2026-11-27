package com.foremen.service.estimateai.ai;

import java.time.Duration;
import java.util.Map;

/** FOR-05-04 — text-in / JSON-text-out boundary over the Gemini SDK (replaceable in tests). */
public interface PlanJsonTransport {

    String generate(String systemInstruction, String userText, Map<String, Object> responseSchema,
                    Duration timeout);

    /** Same with input files attached to the user turn; transports without file support ignore them. */
    default String generate(String systemInstruction, String userText, Map<String, Object> responseSchema,
                            Duration timeout, java.util.List<EstimatePlanClient.Attachment> attachments) {
        return generate(systemInstruction, userText, responseSchema, timeout);
    }
}
