package com.foremen.service.google;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.foremen.config.security.GooglePlacesProperties;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * BUG CONDITION EXPLORATION TEST — FOR-04-bugs Bug 4 (Requirement 1.4 / expected 2.4).
 *
 * <p><b>CRITICAL: this test is expected to FAIL on the current UNFIXED code.</b> Its assertions
 * encode the <em>desired</em> Places API (New) behavior; failure confirms the bug that the
 * production {@link RestClientGooglePlacesClient} still targets the LEGACY Places Web Service
 * ({@code maps.googleapis.com/maps/api/place/*} with a {@code key} query parameter) instead of the
 * Places API (New) ({@code places.googleapis.com/v1/...} with an {@code X-Goog-Api-Key} header).
 *
 * <p>The client exposes a package-visible constructor that accepts a pre-built {@link RestClient},
 * so we bind a {@link MockRestServiceServer} to a {@link RestClient.Builder} and inspect the actual
 * outbound request. No network and no Testcontainers stack are involved — this is a fast unit/slice
 * test.
 *
 * <p>Once Bug 4 is fixed (client migrated to the Places API (New) endpoints + header), these tests
 * SHALL pass.
 *
 * Feature: FOR-04-bugs, Bug 4 (Google Places address autocomplete)
 * Validates: Requirements 1.4, 2.4
 */
class PlacesApiNewExplorationTest {

    private static final String API_KEY = "AIzaSyD-SERVER-SIDE-SECRET-KEY-0123456789";

    private static final GooglePlacesProperties ENABLED_PROPS =
            new GooglePlacesProperties(true, API_KEY);

    @Test
    @DisplayName("autocomplete targets places.googleapis.com/v1 with the X-Goog-Api-Key header "
            + "(FAILS now: legacy maps.googleapis.com URL + key query param)")
    void autocompleteUsesPlacesApiNewEndpointAndHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        // Expected NEW behavior: POST places.googleapis.com/v1/places:autocomplete with an
        // X-Goog-Api-Key header (and an X-Goog-FieldMask shaping the response).
        server.expect(requestTo(Matchers.startsWith(
                        "https://places.googleapis.com/v1/places:autocomplete")))
                .andExpect(header("X-Goog-Api-Key", API_KEY))
                .andRespond(withSuccess(
                        "{\"suggestions\":[]}", MediaType.APPLICATION_JSON));

        RestClientGooglePlacesClient client =
                new RestClientGooglePlacesClient(ENABLED_PROPS, builder.build());

        client.autocomplete("warsaw");

        // If the request went anywhere other than the expected Places API (New) endpoint/header,
        // the mock server records no matching request and verify() fails — which is the current
        // (unfixed) reality because the client still hits the legacy Web Service.
        server.verify();
    }

    @Test
    @DisplayName("details targets places.googleapis.com/v1/places/{id} with the X-Goog-Api-Key header "
            + "(FAILS now: legacy .../details/json URL + key query param)")
    void detailsUsesPlacesApiNewEndpointAndHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        // Expected NEW behavior: GET places.googleapis.com/v1/places/{placeId} with X-Goog-Api-Key.
        server.expect(requestTo(Matchers.startsWith(
                        "https://places.googleapis.com/v1/places/place-1")))
                .andExpect(header("X-Goog-Api-Key", API_KEY))
                .andRespond(withSuccess(
                        "{\"formattedAddress\":\"Warsaw\"}", MediaType.APPLICATION_JSON));

        RestClientGooglePlacesClient client =
                new RestClientGooglePlacesClient(ENABLED_PROPS, builder.build());

        client.details("place-1");

        server.verify();
    }
}
