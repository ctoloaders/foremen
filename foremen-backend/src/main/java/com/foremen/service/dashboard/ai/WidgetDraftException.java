package com.foremen.service.dashboard.ai;

import com.foremen.service.dashboard.config.WidgetConfigValidator.Violation;

import java.util.List;

/**
 * Failure of a draft call. {@link Reason} lets the service map it to a stable error code without
 * parsing messages. Messages never contain the prompt or the model output.
 */
public class WidgetDraftException extends RuntimeException {

    /** Failure class. */
    public enum Reason {
        /** The feature (or its provider) is not configured. */
        DISABLED,
        /** The provider call failed (network, quota, auth, provider 5xx). */
        LLM_UNAVAILABLE,
        /** The provider call exceeded the per-call timeout. */
        TIMEOUT,
        /** The model answered, but the draft stayed invalid after the corrective retry. */
        INVALID_RESPONSE
    }

    private final Reason reason;
    private final List<Violation> violations;

    public WidgetDraftException(Reason reason, String message) {
        this(reason, message, List.of(), null);
    }

    public WidgetDraftException(Reason reason, String message, Throwable cause) {
        this(reason, message, List.of(), cause);
    }

    public WidgetDraftException(Reason reason, String message, List<Violation> violations, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public Reason getReason() {
        return reason;
    }

    /** Validator violations of the last attempt (empty unless {@code INVALID_RESPONSE}). */
    public List<Violation> getViolations() {
        return violations;
    }
}
