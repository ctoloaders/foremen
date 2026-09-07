package com.foremen.service.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import static org.mockito.Mockito.verifyNoInteractions;

import com.foremen.config.security.GooglePlacesProperties;
import com.foremen.controller.model.PlaceDetailsDto;
import com.foremen.controller.model.PlacePredictionDto;
import com.foremen.service.google.GooglePlacesClient.AutocompleteResponse;
import com.foremen.service.google.GooglePlacesClient.DetailsResponse;
import com.foremen.service.google.GooglePlacesClient.DetailsResponse.AddressComponent;
import com.foremen.service.google.GooglePlacesClient.DetailsResponse.Result;
import com.foremen.service.google.GooglePlacesClient.Prediction;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link GooglePlacesService} using a mocked {@link GooglePlacesClient}.
 *
 * <p>Verifies that the service normalizes the raw Google response shapes into the public
 * {@link PlacePredictionDto} / {@link PlaceDetailsDto} DTOs — including {@code Double}→
 * {@link BigDecimal} conversion for latitude/longitude and copying of address components — and that
 * the server-side API key never appears in any produced DTO or its fields (FOR-04-13,
 * Requirements 5.1, 5.2, 5.3). The client injects the key internally, so the service only ever sees
 * a free-text query or a {@code placeId}.
 */
@ExtendWith(MockitoExtension.class)
class GooglePlacesServiceTest {

    /**
     * A representative server-side Google Places API key. The service must never surface this value
     * in any output DTO or field (Requirement 5.3).
     */
    private static final String API_KEY = "AIzaSyD-SERVER-SIDE-SECRET-KEY-0123456789";

    @Mock
    private GooglePlacesClient client;

    private GooglePlacesService service;

    @BeforeEach
    void setUp() {
        // Feature enabled with a (dummy) key so the enabled-guard is a no-op and the normalization
        // paths under test run against the mocked client.
        service = new GooglePlacesService(client, new GooglePlacesProperties(true, API_KEY));
    }

    // --- autocomplete: normalization (Req 5.1) ---

    @Test
    @DisplayName("autocomplete normalizes each raw prediction into a PlacePredictionDto")
    void autocompleteNormalizesPredictions() {
        when(client.autocomplete("warsaw")).thenReturn(new AutocompleteResponse(List.of(
                new Prediction("Warsaw, Poland", "place-1"),
                new Prediction("Warsaw, Indiana, USA", "place-2"))));

        List<PlacePredictionDto> result = service.autocomplete("warsaw");

        assertThat(result).containsExactly(
                new PlacePredictionDto("Warsaw, Poland", "place-1"),
                new PlacePredictionDto("Warsaw, Indiana, USA", "place-2"));
        verify(client).autocomplete("warsaw");
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("autocomplete returns an empty list when the client has no predictions")
    void autocompleteReturnsEmptyListWhenNoPredictions() {
        when(client.autocomplete("nowhere")).thenReturn(new AutocompleteResponse(List.of()));

        List<PlacePredictionDto> result = service.autocomplete("nowhere");

        assertThat(result).isEmpty();
    }

    // --- resolveDetails: normalization (Req 5.2) ---

    @Test
    @DisplayName("resolveDetails normalizes the raw result into a PlaceDetailsDto with BigDecimal geometry and components")
    void resolveDetailsNormalizesResult() {
        DetailsResponse response = new DetailsResponse(new Result(
                "Aleje Jerozolimskie 1, Warsaw, Poland",
                52.2296756,
                21.0122287,
                List.of(
                        new AddressComponent("Warsaw", "Warsaw", List.of("locality", "political")),
                        new AddressComponent("Poland", "PL", List.of("country", "political")))));
        when(client.details("place-1")).thenReturn(response);

        PlaceDetailsDto dto = service.resolveDetails("place-1");

        assertThat(dto).isNotNull();
        assertThat(dto.formattedAddress()).isEqualTo("Aleje Jerozolimskie 1, Warsaw, Poland");
        assertThat(dto.latitude()).isEqualByComparingTo(BigDecimal.valueOf(52.2296756));
        assertThat(dto.longitude()).isEqualByComparingTo(BigDecimal.valueOf(21.0122287));
        assertThat(dto.components()).containsExactly(
                new PlaceDetailsDto.PlaceComponentDto("Warsaw", "Warsaw", List.of("locality", "political")),
                new PlaceDetailsDto.PlaceComponentDto("Poland", "PL", List.of("country", "political")));
        verify(client).details("place-1");
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("resolveDetails returns null latitude/longitude when the raw geometry is null")
    void resolveDetailsHandlesNullGeometry() {
        when(client.details("place-2")).thenReturn(new DetailsResponse(
                new Result("Somewhere", null, null, List.of())));

        PlaceDetailsDto dto = service.resolveDetails("place-2");

        assertThat(dto).isNotNull();
        assertThat(dto.formattedAddress()).isEqualTo("Somewhere");
        assertThat(dto.latitude()).isNull();
        assertThat(dto.longitude()).isNull();
        assertThat(dto.components()).isEmpty();
    }

    @Test
    @DisplayName("resolveDetails returns null when the client has no result")
    void resolveDetailsReturnsNullWhenNoResult() {
        when(client.details("missing")).thenReturn(new DetailsResponse(null));

        PlaceDetailsDto dto = service.resolveDetails("missing");

        assertThat(dto).isNull();
    }

    // --- API key is never exposed (Req 5.3) ---

    @Test
    @DisplayName("the API key never appears in any autocomplete output DTO or field")
    void autocompleteNeverExposesApiKey() {
        when(client.autocomplete(API_KEY)).thenReturn(new AutocompleteResponse(List.of(
                new Prediction("Warsaw, Poland", "place-1"))));

        List<PlacePredictionDto> result = service.autocomplete(API_KEY);

        // Even when the (adversarial) query equals the key, the normalized DTOs carry only the
        // description and placeId returned by the client, never the key itself.
        assertThat(result)
                .allSatisfy(dto -> {
                    assertThat(dto.description()).doesNotContain(API_KEY);
                    assertThat(dto.placeId()).doesNotContain(API_KEY);
                });
        assertThat(result.toString()).doesNotContain(API_KEY);
    }

    @Test
    @DisplayName("the API key never appears in the resolveDetails output DTO or any field")
    void resolveDetailsNeverExposesApiKey() {
        when(client.details("place-1")).thenReturn(new DetailsResponse(new Result(
                "Aleje Jerozolimskie 1, Warsaw, Poland",
                52.2296756,
                21.0122287,
                List.of(new AddressComponent("Warsaw", "Warsaw", List.of("locality"))))));

        PlaceDetailsDto dto = service.resolveDetails("place-1");

        assertThat(dto).isNotNull();
        assertThat(dto.formattedAddress()).doesNotContain(API_KEY);
        assertThat(dto.components())
                .allSatisfy(c -> {
                    assertThat(c.longName()).doesNotContain(API_KEY);
                    assertThat(c.shortName()).doesNotContain(API_KEY);
                    assertThat(c.types()).allSatisfy(t -> assertThat(t).doesNotContain(API_KEY));
                });
        // The full serialized DTO shape carries no trace of the key.
        assertThat(dto.toString()).doesNotContain(API_KEY);
    }

    // --- enabled guard: disabled feature never calls Google (FOR-04-bugs Bug 4, Req 2.4) ---

    @Test
    @DisplayName("autocomplete returns an empty list and never calls the client when the feature is disabled")
    void autocompleteReturnsEmptyAndSkipsClientWhenDisabled() {
        GooglePlacesService disabled =
                new GooglePlacesService(client, new GooglePlacesProperties(false, ""));

        List<PlacePredictionDto> result = disabled.autocomplete("warsaw");

        assertThat(result).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("resolveDetails returns null and never calls the client when the feature is disabled")
    void resolveDetailsReturnsNullAndSkipsClientWhenDisabled() {
        GooglePlacesService disabled =
                new GooglePlacesService(client, new GooglePlacesProperties(false, ""));

        PlaceDetailsDto dto = disabled.resolveDetails("place-1");

        assertThat(dto).isNull();
        verifyNoInteractions(client);
    }
}
