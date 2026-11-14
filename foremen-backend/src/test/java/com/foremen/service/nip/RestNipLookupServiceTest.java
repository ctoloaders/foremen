package com.foremen.service.nip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.foremen.config.nip.NipLookupProperties;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * FOR-11-00 task 2.2 — {@link RestNipLookupService} against {@link MockRestServiceServer}.
 *
 * <p><b>Validates: Requirements 6.3, 6.4, 6.5</b>
 */
class RestNipLookupServiceTest {

    private static final String BASE = "https://registry.example.test/v1/lookup";
    private static final String KEY = "super-secret-key-123";
    private static final String NIP = "5260001246";

    private MockRestServiceServer server;

    private RestNipLookupService service() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new RestNipLookupService(
                new NipLookupProperties(true, BASE, KEY, Duration.ofSeconds(5)), builder);
    }

    @Test
    void sendsGetWithNipQueryAndApiKeyHeader() {
        RestNipLookupService service = service();
        server.expect(requestTo(BASE + "?nip=" + NIP))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Api-Key", KEY))
                .andRespond(withSuccess("{\"name\":\"ACME\"}", MediaType.APPLICATION_JSON));

        assertThat(service.lookup(NIP)).isPresent();
        server.verify();
    }

    @Test
    void mapsAllFieldsAndIgnoresUnknownProperties() {
        RestNipLookupService service = service();
        server.expect(requestTo(BASE + "?nip=" + NIP)).andRespond(withSuccess(
                "{\"nip\":\"" + NIP + "\",\"name\":\"ACME Sp. z o.o.\",\"address\":\"ul. Prosta 1, Warszawa\","
                        + "\"regon\":\"123456789\",\"krs\":\"0000123456\",\"registry\":\"krs\","
                        + "\"extra\":{\"a\":1},\"flag\":true}", MediaType.APPLICATION_JSON));

        Optional<NipLookupResult> result = service.lookup(NIP);

        assertThat(result).contains(new NipLookupResult(NIP, "ACME Sp. z o.o.", "ul. Prosta 1, Warszawa",
                "123456789", "0000123456", NipLookupResult.Registry.KRS));
    }

    @Test
    void missingOptionalFieldsAreNullAndRegistryUnknown() {
        RestNipLookupService service = service();
        server.expect(requestTo(BASE + "?nip=" + NIP))
                .andRespond(withSuccess("{\"name\":\"Jan Kowalski\"}", MediaType.APPLICATION_JSON));

        NipLookupResult result = service.lookup(NIP).orElseThrow();

        assertThat(result.nip()).isEqualTo(NIP);
        assertThat(result.address()).isNull();
        assertThat(result.krs()).isNull();
        assertThat(result.registry()).isEqualTo(NipLookupResult.Registry.UNKNOWN);
    }

    @Test
    void notFoundYieldsEmpty() {
        RestNipLookupService service = service();
        server.expect(requestTo(BASE + "?nip=" + NIP)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(service.lookup(NIP)).isEmpty();
    }

    @Test
    void emptyBodiesYieldEmpty() {
        for (String body : new String[] {"", "{}", "[]", "null", "{\"name\":\"  \"}"}) {
            RestNipLookupService service = service();
            server.expect(requestTo(BASE + "?nip=" + NIP)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            assertThat(service.lookup(NIP)).as(body).isEmpty();
        }
    }

    @Test
    void serverErrorIsUnavailableAndDoesNotLeakKey() {
        RestNipLookupService service = service();
        server.expect(requestTo(BASE + "?nip=" + NIP)).andRespond(withServerError());

        assertThatThrownBy(() -> service.lookup(NIP))
                .isInstanceOf(NipLookupUnavailableException.class)
                .hasMessageContaining("500")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY));
    }

    @Test
    void timeoutIsUnavailableAndDoesNotLeakKey() {
        RestNipLookupService service = service();
        server.expect(requestTo(BASE + "?nip=" + NIP))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> service.lookup(NIP))
                .isInstanceOf(NipLookupUnavailableException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(KEY);
                    assertThat(String.valueOf(e.getCause())).doesNotContain(KEY);
                });
    }

    @Test
    void malformedJsonIsUnavailable() {
        RestNipLookupService service = service();
        server.expect(requestTo(BASE + "?nip=" + NIP))
                .andRespond(withSuccess("<html>oops", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.lookup(NIP)).isInstanceOf(NipLookupUnavailableException.class);
    }

    @Test
    void reportsConfigured() {
        assertThat(service().isConfigured()).isTrue();
    }
}
