package com.foremen.service.bank.connection;

import org.springframework.http.HttpStatus;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;

/**
 * FOR-07-04 (Requirement 12.3; design §Error handling) — maps a {@link ConnectorError} raised during a
 * user action (create / authorize / complete / reconnect / refresh / disable / supported banks) to a
 * {@link ForemenApiException} carrying the localized {@code error.bank.provider.*} message key — never
 * the raw provider text.
 *
 * <p>HTTP status per class: {@code TRANSIENT} → {@code 503} (retry later); every other class →
 * {@code 409} (the request conflicts with the provider / configuration state).
 */
public final class BankConnectorErrors {

    /** 409 when no connector bean exists for the provider or it reports {@code isAvailable() = false}. */
    public static final String PROVIDER_NOT_AVAILABLE = "error.bank.provider.notAvailable";

    private BankConnectorErrors() {
        // Utility class: no instances.
    }

    /** The API exception for a connector failure. */
    public static ForemenApiException toApiException(ConnectorError error) {
        HttpStatus status = error.getErrorClass() == ConnectorErrorClass.TRANSIENT
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.CONFLICT;
        String key = error.getMessageKey() != null
                ? error.getMessageKey()
                : ConnectorError.defaultMessageKey(error.getErrorClass());
        return new ForemenApiException(status, key);
    }

    /** {@code 409 error.bank.provider.notAvailable}. */
    public static ForemenApiException providerNotAvailable() {
        return new ForemenApiException(HttpStatus.CONFLICT, PROVIDER_NOT_AVAILABLE);
    }
}
