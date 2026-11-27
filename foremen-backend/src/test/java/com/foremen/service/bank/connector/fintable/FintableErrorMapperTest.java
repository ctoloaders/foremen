package com.foremen.service.bank.connector.fintable;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.bank.connector.ConnectorErrorClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * FOR-07-04a task 2.2 — status table of Requirement 3.2, {@code Retry-After}, provider type / message
 * (3.3, 3.4) and secret redaction (14.6).
 */
class FintableErrorMapperTest {

    private static String body(String type, String message) {
        return "{\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
    }

    private static FintableConnectorError map(int status, String method, String path, String type, String message) {
        return FintableErrorMapper.fromResponse(status, method, path, body(type, message), null, null);
    }

    @ParameterizedTest(name = "{0} {1} {2} [{3}] -> {4} / {5}")
    @CsvSource({
            "401, GET, /me, unauthenticated, CONFIG, error.bank.fintable.tokenInvalid",
            "403, POST, /sync/conn_1, forbidden, CONFIG, error.bank.fintable.noPlan",
            "403, GET, /sync, forbidden, CONFIG, error.bank.fintable.noPlan",
            "403, POST, /connections/link, forbidden, CONFIG, error.bank.fintable.noPlan",
            "403, POST, /connections/conn_1/link, forbidden, CONFIG, error.bank.fintable.noPlan",
            "403, GET, /accounts, forbidden, CONFIG, error.bank.fintable.forbidden",
            "404, GET, /connections/conn_1, not_found, REAUTH, error.bank.provider.reauth",
            "404, GET, /accounts/acc_1/transactions, not_found, REAUTH, error.bank.provider.reauth",
            "404, DELETE, /connections/conn_1, not_found, REAUTH, error.bank.provider.reauth",
            "404, GET, /institutions, not_found, PROVIDER, error.bank.provider.unavailable",
            "500, GET, /accounts, server_error, TRANSIENT, error.bank.provider.transient",
            "503, GET, /me, unavailable, TRANSIENT, error.bank.provider.transient",
            "400, GET, /accounts, bad_request, PROVIDER, error.bank.provider.unavailable"
    })
    void statusRows(int status, String method, String path, String type, ConnectorErrorClass errorClass,
                    String key) {
        FintableConnectorError error = map(status, method, path, type, "something happened");

        assertThat(error.getErrorClass()).isEqualTo(errorClass);
        assertThat(error.getMessageKey()).isEqualTo(key);
        assertThat(error.getHttpStatus()).isEqualTo(status);
        assertThat(error.getProviderErrorCode()).isEqualTo(type);
        assertThat(error.getMessage()).contains("HTTP " + status).contains("something happened");
    }

    @Test
    @DisplayName("422 on redirect_url -> CONFIG returnHostNotAllowed")
    void unprocessableRedirectUrl() {
        FintableConnectorError error = map(422, "POST", "/connections/link", "validation_error",
                "The redirect_url host is not allowed.");

        assertThat(error.getErrorClass()).isEqualTo(ConnectorErrorClass.CONFIG);
        assertThat(error.getMessageKey()).isEqualTo("error.bank.fintable.returnHostNotAllowed");
    }

    @Test
    @DisplayName("422 connection limit / plan headroom -> LIMIT connectionLimit")
    void unprocessableConnectionLimit() {
        FintableConnectorError limit = map(422, "POST", "/connections/link", "connection_limit",
                "Connection limit reached for your plan.");
        FintableConnectorError headroom = map(422, "POST", "/connections/link", "validation_error",
                "No headroom left.");

        for (FintableConnectorError error : new FintableConnectorError[] {limit, headroom}) {
            assertThat(error.getErrorClass()).isEqualTo(ConnectorErrorClass.LIMIT);
            assertThat(error.getMessageKey()).isEqualTo("error.bank.fintable.connectionLimit");
            assertThat(error.isRetryable()).isFalse();
        }
    }

    @Test
    @DisplayName("422 other -> PROVIDER unavailable")
    void unprocessableOther() {
        FintableConnectorError error = map(422, "POST", "/connections/link", "validation_error",
                "Unknown institution.");

        assertThat(error.getErrorClass()).isEqualTo(ConnectorErrorClass.PROVIDER);
        assertThat(error.getMessageKey()).isEqualTo("error.bank.provider.unavailable");
    }

    @Test
    @DisplayName("429 -> LIMIT rateLimited with Retry-After seconds")
    void rateLimitedWithRetryAfter() {
        FintableConnectorError error = FintableErrorMapper.fromResponse(429, "POST", "/connections/link",
                body("rate_limited", "Too many requests"), "42", null);

        assertThat(error.getErrorClass()).isEqualTo(ConnectorErrorClass.LIMIT);
        assertThat(error.getMessageKey()).isEqualTo("error.bank.fintable.rateLimited");
        assertThat(error.getRetryAfterSeconds()).isEqualTo(42L);
        assertThat(error.getMessage()).contains("retry after 42 s");
        assertThat(error.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("429 without or with an unparsable Retry-After keeps a null value")
    void rateLimitedWithoutRetryAfter() {
        assertThat(FintableErrorMapper.fromResponse(429, "GET", "/sync", null, null, null).getRetryAfterSeconds())
                .isNull();
        assertThat(FintableErrorMapper.fromResponse(429, "GET", "/sync", null, "Wed, 21 Oct 2026 07:28:00 GMT", null)
                .getRetryAfterSeconds()).isNull();
        assertThat(FintableErrorMapper.fromResponse(429, "GET", "/sync", null, "-5", null).getRetryAfterSeconds())
                .isNull();
        assertThat(FintableErrorMapper.fromResponse(429, "GET", "/sync", null, " 7 ", null).getRetryAfterSeconds())
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("Retry-After is only read for 429")
    void retryAfterIgnoredForOtherStatuses() {
        assertThat(FintableErrorMapper.fromResponse(503, "GET", "/me", null, "30", null).getRetryAfterSeconds())
                .isNull();
    }

    @Test
    @DisplayName("provider message is truncated and single-line; query values are not in the message")
    void truncatesMessageAndDropsQuery() {
        String longMessage = "x".repeat(1000);
        FintableConnectorError error = FintableErrorMapper.fromResponse(500, "GET",
                "/accounts?connection_id=conn_secret&workspace_id=ws_1", body("server_error", longMessage), null,
                null);

        assertThat(error.getMessage()).contains("…").hasSizeLessThan(500)
                .doesNotContain("conn_secret").doesNotContain("ws_1");
        assertThat(error.getMessage()).contains("GET /accounts HTTP 500 server_error");
    }

    @Test
    @DisplayName("unparsable or empty body is classified by status only")
    void unparsableBody() {
        FintableConnectorError html = FintableErrorMapper.fromResponse(502, "GET", "/me", "<html>bad gateway</html>",
                null, null);
        FintableConnectorError empty = FintableErrorMapper.fromResponse(401, "GET", "/me", "", null, null);

        assertThat(html.getErrorClass()).isEqualTo(ConnectorErrorClass.TRANSIENT);
        assertThat(html.getProviderErrorCode()).isNull();
        assertThat(html.getMessage()).doesNotContain("<html>");
        assertThat(empty.getMessageKey()).isEqualTo("error.bank.fintable.tokenInvalid");
    }

    @Test
    @DisplayName("a token echoed by the provider is redacted from the message")
    void redactsSecret() {
        String token = "fnt_pat_TOP_SECRET_123";
        FintableConnectorError error = FintableErrorMapper.fromResponse(401, "GET", "/me",
                body("unauthenticated", "Invalid token " + token), null, token);

        assertThat(error.getMessage()).doesNotContain(token).contains("***");
    }

    @Test
    @DisplayName("network failure -> TRANSIENT")
    void transientFailure() {
        FintableConnectorError error = FintableErrorMapper.transientFailure("I/O error", new RuntimeException("x"));

        assertThat(error.getErrorClass()).isEqualTo(ConnectorErrorClass.TRANSIENT);
        assertThat(error.getMessageKey()).isEqualTo("error.bank.provider.transient");
        assertThat(error.getHttpStatus()).isNull();
        assertThat(error.isRetryable()).isTrue();
    }
}
