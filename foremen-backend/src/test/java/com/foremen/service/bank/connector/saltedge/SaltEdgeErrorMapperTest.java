package com.foremen.service.bank.connector.saltedge;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** FOR-07-04 task 5.1 — {@link SaltEdgeErrorMapper} per design §Error mapping (R12.1, R12.3). */
class SaltEdgeErrorMapperTest {

    @ParameterizedTest(name = "{0} → {1} / {2}")
    @CsvSource({
            "WrongClientCredentials,       CONFIG,    error.bank.provider.config",
            "ClientDisabled,               CONFIG,    error.bank.provider.config",
            "ClientPending,                CONFIG,    error.bank.provider.config",
            "ClientRestricted,             CONFIG,    error.bank.provider.config",
            "SignatureNotMatch,            CONFIG,    error.bank.provider.config",
            "ExpiresAtInvalid,             CONFIG,    error.bank.provider.config",
            "PublicKeyNotProvided,         CONFIG,    error.bank.provider.config",
            "MissingSignature,             CONFIG,    error.bank.provider.config",
            "AispConsentExpired,           REAUTH,    error.bank.provider.reauth",
            "AispConsentRevoked,           REAUTH,    error.bank.provider.reauth",
            "AispConsentNotFound,          REAUTH,    error.bank.provider.reauth",
            "ConnectionDisabled,           REAUTH,    error.bank.provider.reauth",
            "InvalidCredentials,           REAUTH,    error.bank.provider.reauth",
            "PartnerConsentRevoked,        REAUTH,    error.bank.provider.reauth",
            "PartnerConsentNotFound,       REAUTH,    error.bank.provider.reauth",
            "PartnerConsentExpired,        REAUTH,    error.bank.provider.reauth",
            "ConnectionNotFound,           REAUTH,    error.bank.provider.reauth",
            "ConnectionCannotBeRefreshed,  LIMIT,     error.bank.provider.limit",
            "BackgroundFetchLimitExceeded, LIMIT,     error.bank.provider.limit",
            "RateLimitExceeded,            LIMIT,     error.bank.provider.limit",
            "FakeConnectionsLimitReached,  LIMIT,     error.bank.provider.connectionLimit",
            "ConnectionLimitReached,       LIMIT,     error.bank.provider.connectionLimit",
            "ProviderUnavailable,          PROVIDER,  error.bank.provider.unavailable",
            "ProviderInactive,             PROVIDER,  error.bank.provider.unavailable",
            "ProviderDisabled,             PROVIDER,  error.bank.provider.unavailable",
            "ProviderKeyNotFound,          PROVIDER,  error.bank.provider.keysRequired",
            "MissingProviderCredentials,   PROVIDER,  error.bank.provider.keysRequired",
            "SomethingNew,                 TRANSIENT, error.bank.provider.transient",
            "InternalServerError,          TRANSIENT, error.bank.provider.transient"
    })
    void mapsDocumentedClasses(String saltEdgeClass, ConnectorErrorClass expected, String expectedKey) {
        ConnectorError error = SaltEdgeErrorMapper.toConnectorError(406, saltEdgeClass, "msg", "req-1");

        assertThat(error.getErrorClass()).isEqualTo(expected);
        assertThat(error.getMessageKey()).isEqualTo(expectedKey);
        assertThat(error.getProviderErrorCode()).isEqualTo(saltEdgeClass);
        assertThat(error.getRequestId()).isEqualTo("req-1");
        assertThat(error.isRetryable()).isEqualTo(expected == ConnectorErrorClass.TRANSIENT);
    }

    @Test
    void parsesErrorBodyAndKeepsRawClassAndMessageForSupport() {
        String body = """
                {"error":{"class":"ProviderKeyNotFound","message":"The chosen provider does not have provider keys",
                "request_id":"313b4518"},"request":{}}""";

        ConnectorError error = SaltEdgeErrorMapper.fromResponse(404, body);

        assertThat(error.getErrorClass()).isEqualTo(ConnectorErrorClass.PROVIDER);
        assertThat(error.getMessageKey()).isEqualTo("error.bank.provider.keysRequired");
        assertThat(error.getProviderErrorCode()).isEqualTo("ProviderKeyNotFound");
        assertThat(error.getRequestId()).isEqualTo("313b4518");
        assertThat(error.getMessage()).contains("HTTP 404", "ProviderKeyNotFound", "does not have provider keys");
    }

    @Test
    void serverErrorWithoutClassIsTransient() {
        ConnectorError error = SaltEdgeErrorMapper.fromResponse(503, "<html>Bad gateway</html>");

        assertThat(error.getErrorClass()).isEqualTo(ConnectorErrorClass.TRANSIENT);
        assertThat(error.getMessageKey()).isEqualTo("error.bank.provider.transient");
        assertThat(error.getProviderErrorCode()).isNull();
    }

    @Test
    void knownClassWinsOverServerStatus() {
        ConnectorError error = SaltEdgeErrorMapper.fromResponse(500,
                "{\"error\":{\"class\":\"ClientPending\",\"message\":\"pending\"}}");

        assertThat(error.getErrorClass()).isEqualTo(ConnectorErrorClass.CONFIG);
    }

    @Test
    void emptyBodyIsTransient() {
        assertThat(SaltEdgeErrorMapper.fromResponse(502, null).getErrorClass())
                .isEqualTo(ConnectorErrorClass.TRANSIENT);
        assertThat(SaltEdgeErrorMapper.fromResponse(502, "  ").getErrorClass())
                .isEqualTo(ConnectorErrorClass.TRANSIENT);
    }

    @Test
    void fromCallbackClass() {
        ConnectorError error = SaltEdgeErrorMapper.fromClass("AispConsentRevoked", "revoked by user");

        assertThat(error.getErrorClass()).isEqualTo(ConnectorErrorClass.REAUTH);
        assertThat(error.getMessage()).doesNotContain("HTTP").contains("AispConsentRevoked");
        assertThat(SaltEdgeErrorMapper.fromClass(null, null).getErrorClass())
                .isEqualTo(ConnectorErrorClass.TRANSIENT);
    }

    @Test
    void longProviderMessageIsTruncatedAndSingleLine() {
        String longMessage = "line1\nline2 " + "x".repeat(1000);

        ConnectorError error = SaltEdgeErrorMapper.toConnectorError(406, "ProviderError", longMessage, null);

        assertThat(error.getMessage()).doesNotContain("\n");
        assertThat(error.getMessage().length()).isLessThan(400);
    }

    @Test
    void transientFailureKeepsCause() {
        RuntimeException cause = new RuntimeException("timeout");

        ConnectorError error = SaltEdgeErrorMapper.transientFailure("I/O", cause);

        assertThat(error.getErrorClass()).isEqualTo(ConnectorErrorClass.TRANSIENT);
        assertThat(error.getCause()).isSameAs(cause);
    }
}
