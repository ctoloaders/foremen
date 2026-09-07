package com.foremen.service.google;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.foremen.config.security.GooglePlacesProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Production {@link GooglePlacesClient} backed by Spring's {@link RestClient}, calling the Google
 * <b>Places API (New)</b> ({@code places.googleapis.com/v1}) — FOR-04-bugs Bug 4, Requirement 2.4.
 *
 * <p>This replaces the LEGACY Places Web Service ({@code maps.googleapis.com/maps/api/place/*} with
 * a {@code key} query parameter). The server-side API key from
 * {@link GooglePlacesProperties#apiKey()} is attached as the {@code X-Goog-Api-Key} HTTP header on
 * every outbound request (never as a query parameter) and is never surfaced back to the caller
 * (Requirement 5.3): this class only returns the raw {@link GooglePlacesClient} shapes, which carry
 * no key. Normalization into the public DTOs is done by {@link GooglePlacesService}.
 *
 * <p>Each request also carries an {@code X-Goog-FieldMask} header shaping the response to only the
 * fields we consume.
 */
@Component
public class RestClientGooglePlacesClient implements GooglePlacesClient {

    private static final String AUTOCOMPLETE_URL =
            "https://places.googleapis.com/v1/places:autocomplete";
    private static final String DETAILS_URL_TEMPLATE =
            "https://places.googleapis.com/v1/places/{placeId}";

    private static final String API_KEY_HEADER = "X-Goog-Api-Key";
    private static final String FIELD_MASK_HEADER = "X-Goog-FieldMask";

    private static final String AUTOCOMPLETE_FIELD_MASK =
            "suggestions.placePrediction.text,suggestions.placePrediction.placeId";
    private static final String DETAILS_FIELD_MASK =
            "formattedAddress,location,addressComponents";

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
        GoogleAutocompleteResponse response = restClient.post()
                .uri(AUTOCOMPLETE_URL)
                .header(API_KEY_HEADER, properties.apiKey())
                .header(FIELD_MASK_HEADER, AUTOCOMPLETE_FIELD_MASK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("input", query))
                .retrieve()
                .body(GoogleAutocompleteResponse.class);

        List<Prediction> predictions = Optional.ofNullable(response)
                .map(GoogleAutocompleteResponse::suggestions)
                .orElseGet(List::of)
                .stream()
                .map(GoogleSuggestion::placePrediction)
                .filter(java.util.Objects::nonNull)
                .map(p -> new Prediction(
                        Optional.ofNullable(p.text()).map(GoogleText::text).orElse(null),
                        p.placeId()))
                .toList();
        return new AutocompleteResponse(predictions);
    }

    @Override
    public DetailsResponse details(String placeId) {
        GooglePlace place = restClient.get()
                .uri(DETAILS_URL_TEMPLATE, placeId)
                .header(API_KEY_HEADER, properties.apiKey())
                .header(FIELD_MASK_HEADER, DETAILS_FIELD_MASK)
                .retrieve()
                .body(GooglePlace.class);

        if (place == null) {
            return new DetailsResponse(null);
        }

        Double lat = Optional.ofNullable(place.location())
                .map(GoogleLocation::latitude)
                .orElse(null);
        Double lng = Optional.ofNullable(place.location())
                .map(GoogleLocation::longitude)
                .orElse(null);

        List<DetailsResponse.AddressComponent> components =
                Optional.ofNullable(place.addressComponents())
                        .orElseGet(List::of)
                        .stream()
                        .map(c -> new DetailsResponse.AddressComponent(
                                c.longText(), c.shortText(), c.types()))
                        .toList();

        return new DetailsResponse(new DetailsResponse.Result(
                place.formattedAddress(), lat, lng, components));
    }

    // --- Raw Places API (New) JSON binding shapes ---

    /** Autocomplete response: {@code { "suggestions": [ { "placePrediction": { ... } } ] } }. */
    private record GoogleAutocompleteResponse(List<GoogleSuggestion> suggestions) {}

    private record GoogleSuggestion(GooglePlacePrediction placePrediction) {}

    private record GooglePlacePrediction(
            GoogleText text,
            @JsonProperty("placeId") String placeId) {}

    private record GoogleText(String text) {}

    /** Place details: {@code { "formattedAddress", "location", "addressComponents" }}. */
    private record GooglePlace(
            @JsonProperty("formattedAddress") String formattedAddress,
            GoogleLocation location,
            @JsonProperty("addressComponents") List<GoogleAddressComponent> addressComponents) {}

    private record GoogleLocation(Double latitude, Double longitude) {}

    private record GoogleAddressComponent(
            @JsonProperty("longText") String longText,
            @JsonProperty("shortText") String shortText,
            List<String> types) {}
}
