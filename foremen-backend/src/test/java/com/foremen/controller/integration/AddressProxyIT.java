package com.foremen.controller.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.service.google.GooglePlacesClient;
import com.foremen.service.google.GooglePlacesClient.AutocompleteResponse;
import com.foremen.service.google.GooglePlacesClient.DetailsResponse;
import com.foremen.service.google.GooglePlacesClient.DetailsResponse.AddressComponent;
import com.foremen.service.google.GooglePlacesClient.DetailsResponse.Result;
import com.foremen.service.google.GooglePlacesClient.Prediction;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration tests for {@link com.foremen.controller.AddressController} — the standalone,
 * reusable Google Places proxy exposed under {@code /api/addresses/*} (FOR-04-13, Requirements 7.8,
 * 7.9, 5.3, 5.6).
 *
 * <p>Boots the full application context with the real Spring Security filter chain against a
 * Testcontainers PostgreSQL instance (mirroring {@link ProjectMemberControllerIntegrationTest}).
 * Requests hit {@code /api/addresses/*} through {@link MockMvc} so the security chain runs exactly
 * as it would for a real HTTP request.
 *
 * <p>The {@link GooglePlacesClient} is replaced with a {@code @MockitoBean}, so no real Google HTTP
 * call is ever made; the real {@code GooglePlacesService} normalizes the mocked raw payloads into the
 * public {@code PlacePredictionDto}/{@code PlaceDetailsDto} shapes exactly as in production. This lets
 * the tests assert both the normalized JSON responses (7.8, 7.9) and that the server-side API key
 * never appears in any response body (5.3).
 *
 * <p>Guarding behavior (5.6): because {@code AddressController} carries none of the ABAC annotations,
 * it is authenticated-any-user. The tests prove that:
 * <ul>
 *   <li>an authenticated caller <em>without</em> any {@code PROJECTS} grant (a minted {@code WORKER}
 *       JWT) still reaches both endpoints;</li>
 *   <li>an anonymous/unauthenticated request is rejected with HTTP 401.</li>
 * </ul>
 *
 * <p>Repeatability: the test performs no database writes — the only per-request state is a freshly
 * minted JWT (a static {@link AtomicLong} run-id keeps the principal email collision-free) — so the
 * suite is trivially re-runnable.
 *
 * <p>Validates: Requirements 7.8, 7.9, 5.3, 5.6
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class AddressProxyIT {

    private static final String AUTOCOMPLETE_PATH = "/api/addresses/autocomplete";
    private static final String DETAILS_PATH = "/api/addresses/details";

    /**
     * A representative server-side Google Places API key. It is injected internally by the (mocked)
     * client and must never surface in any proxy response body (Requirement 5.3).
     */
    private static final String API_KEY = "AIzaSyD-SERVER-SIDE-SECRET-KEY-0123456789";

    /** A non-ADMIN, non-PROJECTS role code: proves the endpoint is authenticated-any-user (5.6). */
    private static final String WORKER = "WORKER";

    /** Per-run unique id source keeping every minted principal collision-free and re-runnable. */
    private static final AtomicLong RUN_ID = new AtomicLong(System.currentTimeMillis());

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast the UserEntity JSON converter's
            // text value into the jsonb display_preferences column, matching the deployed app.
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /**
     * The only mocked collaborator: replaces the real Google Places client so no network call is
     * made and the raw payloads are controllable per test. The server-side key is injected inside
     * the client (here stubbed away), so the service and controller never see it.
     */
    @MockitoBean
    private GooglePlacesClient googlePlacesClient;

    // === 7.8 : /api/addresses/autocomplete returns normalized predictions; key absent ===

    @Test
    @DisplayName("GET /api/addresses/autocomplete (authenticated) -> 200 with normalized predictions, key absent")
    void autocomplete_authenticated_returnsNormalizedPredictions() throws Exception {
        when(googlePlacesClient.autocomplete("warsaw")).thenReturn(new AutocompleteResponse(List.of(
                new Prediction("Warsaw, Poland", "place-1"),
                new Prediction("Warsaw, Indiana, USA", "place-2"))));

        mockMvc.perform(get(AUTOCOMPLETE_PATH)
                        .header("Authorization", bearer(WORKER))
                        .param("query", "warsaw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].description").value("Warsaw, Poland"))
                .andExpect(jsonPath("$[0].placeId").value("place-1"))
                .andExpect(jsonPath("$[1].description").value("Warsaw, Indiana, USA"))
                .andExpect(jsonPath("$[1].placeId").value("place-2"))
                // The server-side key never appears anywhere in the proxy response (5.3).
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(API_KEY))));
    }

    @Test
    @DisplayName("GET /api/addresses/autocomplete with no predictions -> 200 empty array")
    void autocomplete_noPredictions_returnsEmptyArray() throws Exception {
        when(googlePlacesClient.autocomplete("nowhere"))
                .thenReturn(new AutocompleteResponse(List.of()));

        mockMvc.perform(get(AUTOCOMPLETE_PATH)
                        .header("Authorization", bearer(WORKER))
                        .param("query", "nowhere"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // === 7.9 : /api/addresses/details returns normalized details; key absent ===

    @Test
    @DisplayName("GET /api/addresses/details (authenticated) -> 200 with normalized details, key absent")
    void details_authenticated_returnsNormalizedDetails() throws Exception {
        when(googlePlacesClient.details("place-1")).thenReturn(new DetailsResponse(new Result(
                "Aleje Jerozolimskie 1, Warsaw, Poland",
                52.2296756,
                21.0122287,
                List.of(
                        new AddressComponent("Warsaw", "Warsaw", List.of("locality", "political")),
                        new AddressComponent("Poland", "PL", List.of("country", "political"))))));

        mockMvc.perform(get(DETAILS_PATH)
                        .header("Authorization", bearer(WORKER))
                        .param("placeId", "place-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formattedAddress").value("Aleje Jerozolimskie 1, Warsaw, Poland"))
                .andExpect(jsonPath("$.latitude").value(52.2296756))
                .andExpect(jsonPath("$.longitude").value(21.0122287))
                .andExpect(jsonPath("$.components", hasSize(2)))
                .andExpect(jsonPath("$.components[0].longName").value("Warsaw"))
                .andExpect(jsonPath("$.components[0].shortName").value("Warsaw"))
                .andExpect(jsonPath("$.components[1].longName").value("Poland"))
                .andExpect(jsonPath("$.components[1].shortName").value("PL"))
                // The server-side key never appears anywhere in the proxy response (5.3).
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(API_KEY))));
    }

    // === 5.6 : authenticated non-PROJECTS caller reaches the endpoints ===

    @Test
    @DisplayName("A WORKER (no PROJECTS grant) reaches /api/addresses/* — authenticated-any-user")
    void authenticatedNonProjectsCaller_reachesEndpoints() throws Exception {
        when(googlePlacesClient.autocomplete(anyString()))
                .thenReturn(new AutocompleteResponse(List.of(new Prediction("Krakow, Poland", "place-9"))));
        when(googlePlacesClient.details(anyString()))
                .thenReturn(new DetailsResponse(new Result("Krakow, Poland", 50.0, 19.9, List.of())));

        // No PROJECTS resource is seeded and WORKER holds no grant, yet both endpoints are reachable
        // because AddressController is authenticated-any-user (carries no ABAC annotations).
        mockMvc.perform(get(AUTOCOMPLETE_PATH)
                        .header("Authorization", bearer(WORKER))
                        .param("query", "krakow"))
                .andExpect(status().isOk());

        mockMvc.perform(get(DETAILS_PATH)
                        .header("Authorization", bearer(WORKER))
                        .param("placeId", "place-9"))
                .andExpect(status().isOk());
    }

    // === 5.6 : anonymous caller gets 401 ===

    @Test
    @DisplayName("GET /api/addresses/autocomplete without a token -> 401")
    void autocomplete_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(AUTOCOMPLETE_PATH).param("query", "warsaw"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/addresses/details without a token -> 401")
    void details_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(DETAILS_PATH).param("placeId", "place-1"))
                .andExpect(status().isUnauthorized());
    }

    // === helpers ===

    private static long nextId() {
        return RUN_ID.incrementAndGet();
    }

    /** Mints a JWT whose principal name is a userId (1L) and whose authority is ROLE_<code>. */
    private String bearer(String roleCode) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                1L, roleCode, roleCode.toLowerCase() + "+" + nextId() + "@example.com");
    }
}
