package com.foremen.service.bank.connector;

import java.util.Objects;

/**
 * FOR-07-04 (Requirement 4; design §Error mapping): the single exception type thrown by
 * {@link BankFeedConnector} implementations.
 *
 * <p>Carries the {@link ConnectorErrorClass}, the provider's own error code (e.g. Salt Edge
 * {@code error.class}), a localized message key ({@code error.bank.provider.*}) and the provider
 * request id. The message never contains secrets or request bodies.
 */
public class ConnectorError extends RuntimeException {

    private final ConnectorErrorClass errorClass;
    private final String providerErrorCode;
    private final String messageKey;
    private final String requestId;

    public ConnectorError(ConnectorErrorClass errorClass, String providerErrorCode, String messageKey,
                          String message, String requestId, Throwable cause) {
        super(message, cause);
        this.errorClass = Objects.requireNonNull(errorClass, "errorClass");
        this.providerErrorCode = providerErrorCode;
        this.messageKey = messageKey != null ? messageKey : defaultMessageKey(errorClass);
        this.requestId = requestId;
    }

    public ConnectorError(ConnectorErrorClass errorClass, String providerErrorCode, String messageKey, String message) {
        this(errorClass, providerErrorCode, messageKey, message, null, null);
    }

    public ConnectorError(ConnectorErrorClass errorClass, String message) {
        this(errorClass, null, null, message, null, null);
    }

    public ConnectorError(ConnectorErrorClass errorClass, String message, Throwable cause) {
        this(errorClass, null, null, message, null, cause);
    }

    /** Default {@code error.bank.provider.*} key per class (design §Error mapping). */
    public static String defaultMessageKey(ConnectorErrorClass errorClass) {
        return switch (errorClass) {
            case CONFIG -> "error.bank.provider.config";
            case REAUTH -> "error.bank.provider.reauth";
            case LIMIT -> "error.bank.provider.limit";
            case PROVIDER -> "error.bank.provider.unavailable";
            case TRANSIENT -> "error.bank.provider.transient";
        };
    }

    public ConnectorErrorClass getErrorClass() {
        return errorClass;
    }

    public String getProviderErrorCode() {
        return providerErrorCode;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getRequestId() {
        return requestId;
    }

    public boolean isRetryable() {
        return errorClass.isRetryable();
    }
}
