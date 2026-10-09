package com.foremen.exception;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A business-rule validation failure that reports <em>multiple</em> offending fields at once (HTTP
 * 400). Unlike {@link ForemenApiException}, which carries a single message code, this exception
 * carries a {@code field -> messageCode} map so the response can list every offending field in one
 * body (FOR-05-09 Requirement 13 criterion 4, {@code Worker_Record_Flow}).
 *
 * <p>Each value in {@link #getFieldErrors()} is a <b>message code</b> (not a literal message), so the
 * {@code ForemenControllerAdvice} resolves it to the request language before building the response —
 * mirroring how {@link ForemenApiException#getMessageCode()} is resolved.
 */
@Getter
public class FieldValidationException extends RuntimeException {

    /** Insertion-ordered {@code field -> messageCode} entries, one per offending field. */
    private final Map<String, String> fieldErrors;

    public FieldValidationException(Map<String, String> fieldErrors) {
        super("error.validation");
        this.fieldErrors = fieldErrors == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fieldErrors);
    }
}
