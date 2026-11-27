package com.foremen.service.estimateai;

import java.util.Map;

/**
 * FOR-05-04 rework (Requirement 24.7; design R3) — an expected rejection of one {@code assign_work} call.
 * {@code code} is one of {@link WarningCodes#UNKNOWN_CODE}, {@link WarningCodes#NO_PRICE},
 * {@link WarningCodes#UNSUPPORTED_UNIT} or {@link WarningCodes#EDIT_REJECTED} (the {@code params.reason}
 * names the cause: {@code UNKNOWN_ROOM}, {@code NEGATIVE_VOLUME}, {@code RATIONALE_MISSING},
 * {@code JOB_NOT_ACTIVE}, {@code NO_ROOMS}). It is thrown before anything is written; the call's
 * transaction rolls back.
 */
public class AssignRejected extends RuntimeException {

    private final String code;
    private final transient Map<String, Object> params;

    public AssignRejected(String code, Map<String, Object> params) {
        super(code + (params == null || params.isEmpty() ? "" : " " + params));
        this.code = code;
        this.params = params == null ? Map.of() : Map.copyOf(params);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> params() {
        return params;
    }
}
