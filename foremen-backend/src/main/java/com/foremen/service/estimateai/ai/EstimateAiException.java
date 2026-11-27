package com.foremen.service.estimateai.ai;

/**
 * FOR-05-04 — failure of an estimate-AI model call (planner or chat). The {@link Reason} is mapped
 * by the worker/controllers to {@code error.estimateai.*}.
 */
public class EstimateAiException extends RuntimeException {

    public enum Reason { DISABLED, TIMEOUT, LLM_UNAVAILABLE, INVALID_RESPONSE, PLAN_INVALID }

    private final Reason reason;

    public EstimateAiException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public EstimateAiException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
