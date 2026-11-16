package com.foremen.exception.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * Standard error envelope. {@code fieldErrors} lists offending fields; {@code params} (FOR-05-02,
 * Requirement 19.4) carries optional structured values of the error (omitted when absent).
 * {@code code} is the machine-readable message code of a {@code ForemenApiException}
 * (e.g. {@code error.positionApproval.stale}); omitted for errors that do not come from one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    Instant timestamp,
    int status,
    String error,
    String message,
    String path,
    Map<String, String> fieldErrors,
    Map<String, Object> params,
    String code
) {
    public ErrorResponse(Instant timestamp, int status, String error, String message, String path,
                         Map<String, String> fieldErrors, Map<String, Object> params) {
        this(timestamp, status, error, message, path, fieldErrors, params, null);
    }

    public ErrorResponse(Instant timestamp, int status, String error, String message, String path,
                         Map<String, String> fieldErrors) {
        this(timestamp, status, error, message, path, fieldErrors, null, null);
    }

    public ErrorResponse(Instant timestamp, int status, String error, String message, String path) {
        this(timestamp, status, error, message, path, null, null, null);
    }
}
