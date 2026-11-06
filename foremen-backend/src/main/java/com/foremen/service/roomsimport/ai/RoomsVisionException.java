package com.foremen.service.roomsimport.ai;

/**
 * Failure of a vision call. {@link Reason} lets the pipeline map the failure to a stable error code
 * without parsing messages. Messages never contain user content.
 */
public class RoomsVisionException extends RuntimeException {

    /** Failure class. */
    public enum Reason {
        /** The feature (or its provider) is not configured. */
        DISABLED,
        /** The provider call failed (network, quota, auth, provider 5xx). */
        LLM_UNAVAILABLE,
        /** The provider call exceeded the per-call timeout. */
        TIMEOUT,
        /** The provider answered, but not with a valid structured response (after the retry). */
        INVALID_RESPONSE
    }

    private final Reason reason;

    public RoomsVisionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RoomsVisionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
