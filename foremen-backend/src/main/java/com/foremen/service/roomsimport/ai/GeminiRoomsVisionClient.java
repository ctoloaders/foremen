package com.foremen.service.roomsimport.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.service.roomsimport.ai.RoomsVisionException.Reason;
import com.foremen.service.roomsimport.extract.PageImage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * FOR-05-02 (Requirements 3.1, 5.1, 13.1, 13.2, 13.4, 13.5) — Gemini-backed {@link RoomsVisionClient}.
 *
 * <p>Each call is a structured-output request. The answer is parsed and structurally validated
 * ({@link RoomsVisionResponseValidator}); an invalid answer is retried exactly once (bounded) with a
 * corrective suffix, then fails with {@link Reason#INVALID_RESPONSE}. Provider failures are not
 * retried. Every call has a timeout. INFO logs carry only sizes and durations, never user content.
 */
@Slf4j
@Component
@ConditionalOnExpression("${foremen.rooms-import.enabled:false} and !${foremen.rooms-import.llm.stub:false}")
public class GeminiRoomsVisionClient implements RoomsVisionClient {

    /** Default per-call timeout. */
    public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(90);
    /** One initial attempt plus one retry. */
    static final int MAX_ATTEMPTS = 2;

    private final GeminiJsonTransport transport;
    private final Duration callTimeout;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    public GeminiRoomsVisionClient(RoomsImportProperties properties) {
        this(new GenAiJsonTransport(properties), DEFAULT_CALL_TIMEOUT);
    }

    GeminiRoomsVisionClient(GeminiJsonTransport transport, Duration callTimeout) {
        this.transport = transport;
        this.callTimeout = callTimeout;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public DetectResponse detectRooms(DetectRequest request) {
        return call("detect", RoomsImportPrompts.DETECT_SYSTEM, RoomsImportPrompts.detectUser(request),
                request.images(), RoomsVisionSchemas.detect(), DetectResponse.class,
                RoomsVisionResponseValidator::validate);
    }

    @Override
    public MeasureResponse measureRoom(MeasureRequest request) {
        List<PageImage> images = request.image() == null ? List.of() : List.of(request.image());
        return call("measure", RoomsImportPrompts.MEASURE_SYSTEM, RoomsImportPrompts.measureUser(request),
                images, RoomsVisionSchemas.measure(), MeasureResponse.class,
                RoomsVisionResponseValidator::validate);
    }

    private <T> T call(String op, String system, String user, List<PageImage> images,
                       Map<String, Object> schema, Class<T> type, Function<T, String> validator) {
        String prompt = user;
        String problem = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long started = System.nanoTime();
            String json = transport.generate(system, prompt, images, schema, callTimeout);
            long ms = (System.nanoTime() - started) / 1_000_000;
            T parsed = null;
            try {
                if (json == null || json.isBlank()) {
                    problem = "empty answer";
                } else {
                    parsed = mapper.readValue(stripFence(json), type);
                    problem = validator.apply(parsed);
                }
            } catch (JsonProcessingException e) {
                problem = "malformed JSON";
            }
            // Sizes and timings only — never the prompt or the model output (13.5).
            log.info("rooms-import {} attempt={} ms={} images={} valid={}", op, attempt, ms, images.size(),
                    problem == null);
            if (problem == null) {
                return parsed;
            }
            prompt = user + RoomsImportPrompts.RETRY_SUFFIX;
        }
        throw new RoomsVisionException(Reason.INVALID_RESPONSE,
                "Gemini " + op + " returned an invalid response after " + MAX_ATTEMPTS + " attempts: " + problem);
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
