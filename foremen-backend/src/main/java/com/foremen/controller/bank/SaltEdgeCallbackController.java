package com.foremen.controller.bank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.PublicBaseUrl;
import com.foremen.service.bank.connector.saltedge.SaltEdgeCallbackHandler;
import com.foremen.service.bank.connector.saltedge.SaltEdgeCallbackReceived;
import com.foremen.service.bank.connector.saltedge.SaltEdgeCallbackVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FOR-07-04 (Requirements 10.1–10.9, 21.5, 24.2; design §Callbacks): the Salt Edge callback endpoints
 * {@code POST /api/bank-feed/saltedge/callbacks/{type}}, {@code type ∈ {success, fail, notify, destroy}}.
 *
 * <h2>Intentionally unguarded (per {@code entity-creation-rules})</h2>
 * The caller is Salt Edge, not a Foremen principal, so this controller carries <b>none</b> of the three
 * permission annotations ({@code @PermissionResource} / {@code @PermissionOperation} /
 * {@code @RequiresPermission}) — {@code PermissionAnnotationValidator} treats it as deliberately
 * unguarded, like {@code AuthController} and {@code PaymentWebhookController}. The path is
 * {@code permitAll} in {@code SecurityConfig}; authenticity comes solely from the signature (R21.5).
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>unknown {@code type} → {@code 404};</li>
 *   <li>the raw body is read as bytes, capped at {@link #MAX_BODY_BYTES} (1 MB) → {@code 413} when
 *       larger (R24.2);</li>
 *   <li>{@code Signature} / {@code Signature-key-version} are verified against
 *       {@link PublicBaseUrl#callbackUrl(String)} <b>before</b> any parsing → {@code 401
 *       error.bank.callback.unverified} otherwise (R10.2);</li>
 *   <li>the verified body is parsed and a {@link SaltEdgeCallbackReceived} is published; the response
 *       is {@code 200 {}} immediately and {@link SaltEdgeCallbackHandler} acts asynchronously (R10.3).
 *       An unparsable verified body is logged and answered {@code 200} without side effects.</li>
 * </ol>
 * Responses never carry payload details.
 */
@RestController
@RequestMapping("/api/bank-feed/saltedge/callbacks")
public class SaltEdgeCallbackController {

    private static final Logger log = LoggerFactory.getLogger(SaltEdgeCallbackController.class);

    /** Body size limit (R24.2). */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    public static final String SIGNATURE_HEADER = "Signature";
    public static final String KEY_VERSION_HEADER = "Signature-key-version";

    public static final String UNVERIFIED = "error.bank.callback.unverified";
    public static final String TOO_LARGE = "error.bank.callback.tooLarge";
    static final String NOT_FOUND = "error.entity.not.found";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SaltEdgeCallbackVerifier verifier;
    private final PublicBaseUrl publicBaseUrl;
    private final ApplicationEventPublisher events;

    public SaltEdgeCallbackController(SaltEdgeCallbackVerifier verifier,
                                      PublicBaseUrl publicBaseUrl,
                                      ApplicationEventPublisher events) {
        this.verifier = verifier;
        this.publicBaseUrl = publicBaseUrl;
        this.events = events;
    }

    @PostMapping("/{type}")
    public ResponseEntity<Map<String, Object>> callback(@PathVariable("type") String type,
                                                        HttpServletRequest request) {
        if (!SaltEdgeCallbackReceived.isKnownType(type)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        byte[] body = readBody(request);
        if (!verified(type, body, request)) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, UNVERIFIED);
        }
        Optional<SaltEdgeCallbackReceived> parsed = SaltEdgeCallbackReceived.parse(type, body, MAPPER);
        if (parsed.isEmpty()) {
            log.warn("Verified Salt Edge {} callback has no usable data object; ignored", type);
        } else {
            events.publishEvent(parsed.get());
        }
        return ResponseEntity.ok(Map.of());
    }

    private boolean verified(String type, byte[] body, HttpServletRequest request) {
        String callbackUrl;
        try {
            callbackUrl = publicBaseUrl.callbackUrl(type);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("Salt Edge {} callback rejected: public base URL is not configured", type);
            return false;
        }
        boolean ok = verifier.verify(callbackUrl, body,
                request.getHeader(SIGNATURE_HEADER), request.getHeader(KEY_VERSION_HEADER));
        if (!ok) {
            log.warn("Salt Edge {} callback rejected: signature not verified", type);
        }
        return ok;
    }

    /** Reads at most {@link #MAX_BODY_BYTES}; larger bodies → {@code 413}. */
    private static byte[] readBody(HttpServletRequest request) {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            throw new ForemenApiException(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE);
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new ForemenApiException(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE);
            }
            return body;
        } catch (IOException e) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, UNVERIFIED);
        }
    }
}
