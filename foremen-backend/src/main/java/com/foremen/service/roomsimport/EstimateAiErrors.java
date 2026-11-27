package com.foremen.service.roomsimport;

/** FOR-05-04 stable message / failure-reason codes of the {@code ESTIMATE} job (Requirement 4.6). */
public final class EstimateAiErrors {

    private EstimateAiErrors() {
    }

    public static final String UNAVAILABLE = "error.estimateai.unavailable";
    public static final String NO_ROOMS_START = "error.estimateai.no_rooms";
    public static final String ESTIMATE_LOCKED = "error.estimate.locked";
    public static final String FAILED_LLM_UNAVAILABLE = "error.estimateai.failed.llm_unavailable";
    public static final String FAILED_NO_ROOMS = "error.estimateai.failed.no_rooms";
    public static final String FAILED_PLAN_INVALID = "error.estimateai.failed.plan_invalid";
    public static final String FAILED_TIMEOUT = "error.estimateai.failed.timeout";
}
