package com.foremen.service.google;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.foremen.config.security.GooglePlacesProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Production {@link GooglePlacesClient} backed by Spring's {@link RestClient}, calling the Google
 * Places Web Service (FOR-04-13, Requirement 5.1, 5.2).
 *
 * <p>The server-side API key from {@link GooglePlacesProperties#apiKey()} is attached as the
 * {@code key} query parameter on every outbound request and is never surfaced back to the caller
 * (Requirement 5.3): this class only returns the raw {@link GooglePlacesClient} shapes, which carry
 * no key. Normalization into the public DTOs is done by {@link GooglePlacesService}.
 */
@Component
public class RestClientGooglePlacesClient implements GooglePlacesClient {

    private static final String AUTOCOMPLETE_URL =
            "https://maps.googleapis.com/maps/api/place/autocomplete/json";
    private static final String DETAILS_URL =
            "https://maps.googleapis.com/maps/api/place/details/json";

    private final GooglePlacesProperties properties;
    private final RestClient restClient;

    @Autowired
    public RestClientGooglePlacesClient(GooglePlacesProperties properties) {
        this(properties, RestClient.create());
    }

    /** Package-visible constructor allowing a pre-built {@link RestClient} to be injected in tests. */
    RestClientGooglePlacesClient(GooglePlacesProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public AutocompleteResponse autocomplete(String query) {
        GoogleAutocompleteResponse response = restClient.get()
                .uri(AUTOCOMPLETE_URL, uri -> uri
                        .queryParam("input", query)
                        .queryParam("key", properties.apiKey())
                        .build())
                .retrieve()
                .body(GoogleAutocompleteResponse.class);

        List<Prediction> predictions = Optional.ofNullable(response)
                .map(GoogleAutocompleteResponse::predictions)
                .orElseGet(List::of)
                .stream()
                .map(p -> new Prediction(p.description(), p.placeId()))
                .toList();
        return new AutocompleteResponse(predictions);
    }

    @Override
    public DetailsResponse details(String placeId) {
        GoogleDetailsResponse response = restClient.get()
                .uri(DETAILS_URL, uri -> uri
                        .queryParam("place_id", placeId)
                        .queryParam("key", properties.apiKey())
                        .build())
                .retrieve()
                .body(GoogleDetailsResponse.class);

        GoogleResult result = Optional.ofNullable(response)
                .map(GoogleDetailsResponse::result)
                .orElse(null);
        if (result == null) {
            return new DetailsResponse(null);
        }

        Double lat = Optional.ofNullable(result.geometry())
                .map(GoogleGeometry::location)
                .map(GoogleLocation::lat)
                .orElse(null);
        Double lng = Optional.ofNullable(result.geometry())
                .map(GoogleGeometry::location)
                .map(GoogleLocation::lng)
                .orElse(null);

        List<DetailsResponse.AddressComponent> components =
                Optional.ofNullable(result.addressComponents())
                        .orElseGet(List::of)
                        .stream()
                        .map(c -> new DetailsResponse.AddressComponent(
                                c.longName(), c.shortName(), c.types()))
                        .toList();

        return new DetailsResponse(new DetailsResponse.Result(
                result.formattedAddress(), lat, lng, components));
    }

    // --- Raw Google JSON binding shapes (Google's snake_case field names) ---

    private record GoogleAutocompleteResponse(List<GooglePrediction> predictions) {}

    private record GooglePrediction(
            String description,
            @JsonProperty("place_id") String placeId) {}

    private record GoogleDetailsResponse(GoogleResult result) {}

    private record GoogleResult(
            @JsonProperty("formatted_address") String formattedAddress,
            GoogleGeometry geometry,
            @JsonProperty("address_components") List<GoogleAddressComponent> addressComponents) {}

    private record GoogleGeometry(GoogleLocation location) {}

    private record GoogleLocation(Double lat, Double lng) {}

    private record GoogleAddressComponent(
            @JsonProperty("long_name") String longName,
            @JsonProperty("short_name") String shortName,
            List<String> types) {}
}
