package com.foremen.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

@Getter
public class ForemenApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final String messageCode;
    private final Object[] messageParams;

    /**
     * FOR-05-02 (Requirement 1.8, 19.4) — optional <b>named</b> params echoed in the error envelope
     * ({@code params}), for clients that need structured values (e.g. {@code jobId}, {@code stage}).
     * Empty by default; independent of the positional {@link #messageParams} used for the message text.
     */
    private Map<String, Object> params = Collections.emptyMap();

    /**
     * FOR-05-02 (Requirement 1.8) — optional extra response headers (e.g. {@code Retry-After}) the
     * exception handler adds to the error response. Empty by default.
     */
    private Map<String, String> headers = Collections.emptyMap();

    public ForemenApiException(HttpStatusCode status, String messageCode, Object... messageParams) {
        super(messageCode);
        this.status = status;
        this.messageCode = messageCode;
        this.messageParams = messageParams != null ? messageParams : new Object[0];
    }

    public ForemenApiException(HttpStatusCode status, String messageCode) {
        this(status, messageCode, new Object[0]);
    }

    /** Sets the named params of the error envelope (insertion order preserved). Returns {@code this}. */
    public ForemenApiException withParams(Map<String, ?> namedParams) {
        this.params = namedParams == null || namedParams.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(namedParams));
        return this;
    }

    /** Adds one extra response header. Returns {@code this}. */
    public ForemenApiException withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(this.headers);
        copy.put(name, value);
        this.headers = Collections.unmodifiableMap(copy);
        return this;
    }
}
