package com.foremen.exception.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * Standard error envelope. {@code fieldErrors} lists offending fields; {@code params} (FOR-05-02,
 * Requirement 19.4) carries optional structured values of the error (omitted when absent).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    Instant timestamp,
    int status,
    String error,
    String message,
    String path,
    Map<String, String> fieldErrors,
    Map<String, Object> params
) {
    public ErrorResponse(Instant timestamp, int status, String error, String message, String path,
                         Map<String, String> fieldErrors) {
        this(timestamp, status, error, message, path, fieldErrors, null);
    }

    public ErrorResponse(Instant timestamp, int status, String error, String message, String path) {
        this(timestamp, status, error, message, path, null, null);
    }
}
