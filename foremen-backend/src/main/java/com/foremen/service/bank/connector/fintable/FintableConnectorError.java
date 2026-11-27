package com.foremen.service.bank.connector.fintable;

import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;

/**
 * FOR-07-04a (Requirement 3.2, 3.4): a {@link ConnectorError} from Fintable that also carries the HTTP
 * status and, for {@code 429}, the {@code Retry-After} seconds (the parameter of
 * {@code error.bank.fintable.rateLimited}).
 */
public class FintableConnectorError extends ConnectorError {

    private final Integer httpStatus;
    private final Long retryAfterSeconds;

    public FintableConnectorError(ConnectorErrorClass errorClass, String providerErrorCode, String messageKey,
                                  String message, Integer httpStatus, Long retryAfterSeconds, Throwable cause) {
        super(errorClass, providerErrorCode, messageKey, message, null, cause);
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** HTTP status, or {@code null} for network failures. */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /** {@code Retry-After} in seconds (only on {@code 429}, when the header was present and numeric). */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
