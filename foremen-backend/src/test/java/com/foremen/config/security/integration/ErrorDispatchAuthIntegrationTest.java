package com.foremen.config.security.integration;

import com.foremen.config.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for error responses being masked as 401 (FOR-03-08 follow-up).
 *
 * <p>A request with a valid bearer token that fails after MVC dispatch (an unsupported
 * {@code Content-Type} such as {@code text/plain} on a JSON endpoint, or an unknown path) is
 * forwarded to {@code /error}. The forwarded dispatch carries no JWT, so before the {@code /error}
 * permit rule in {@code SecurityConfig} the catch-all replaced the real status with
 * {@code 401 error.auth.unauthorized}.
 *
 * <p>MockMvc does not perform the container error forward, so these tests send real HTTP requests
 * to a random port to exercise the same dispatch path as the deployed server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("integration-test")
class ErrorDispatchAuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("POST JSON endpoint with text/plain body and a valid token returns 415, not 401")
    void unsupportedMediaTypeKeepsRealStatus() throws Exception {
        String token = adminToken();

        HttpResponse<String> response = send(
                HttpRequest.newBuilder(uri("/api/projects"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "text/plain;charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")));

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.body()).doesNotContain("error.auth.unauthorized");
    }

    @Test
    @DisplayName("Unknown path with a valid token returns 404, not 401")
    void unknownPathKeepsRealStatus() throws Exception {
        String token = adminToken();

        HttpResponse<String> response = send(
                HttpRequest.newBuilder(uri("/api/does-not-exist-" + UUID.randomUUID()))
                        .header("Authorization", "Bearer " + token)
                        .GET());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("POST JSON endpoint with text/plain body and no token still returns 401")
    void unauthenticatedRequestStillReturns401() throws Exception {
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(uri("/api/projects"))
                        .header("Content-Type", "text/plain;charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")));

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("POST JSON endpoint with application/json and a valid token reaches validation (400)")
    void jsonBodyReachesValidation() throws Exception {
        String token = adminToken();

        HttpResponse<String> response = send(
                HttpRequest.newBuilder(uri("/api/projects"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    private String adminToken() {
        return jwtTokenProvider.generateAccessToken(
                1L, "ADMIN", "sec-error+" + UUID.randomUUID() + "@example.com");
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
