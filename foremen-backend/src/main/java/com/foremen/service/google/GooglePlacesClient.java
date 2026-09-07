package com.foremen.service.google;

import java.util.List;

/**
 * Thin abstraction over the two Google Places Web Service HTTP calls used by the address proxy
 * (FOR-04-13, Requirement 5.1, 5.2). Implementations inject the server-side API key on every
 * request so it never reaches the caller (Requirement 5.3).
 *
 * <p>The interface returns the raw (but minimally-typed) Google payload shapes so it stays trivially
 * mockable in tests (Requirement 7.8, 7.9); normalization into the public
 * {@link com.foremen.controller.model.PlacePredictionDto} /
 * {@link com.foremen.controller.model.PlaceDetailsDto} shapes is done by
 * {@link GooglePlacesService}. Neither the interface nor its raw shapes ever carry the key.
 */
public interface GooglePlacesClient {

    /**
     * Calls the Google Places Autocomplete API for the given free-text query.
     *
     * @param query the free-text address query typed by the user
     * @return the raw predictions (never {@code null}; may be empty)
     */
    AutocompleteResponse autocomplete(String query);

    /**
     * Calls the Google Places Details API for the given place identifier.
     *
     * @param placeId the Google-assigned {@code place_id}
     * @return the raw details result (never {@code null})
     */
    DetailsResponse details(String placeId);

    /**
     * Raw Google Places Autocomplete response: a list of predictions.
     *
     * @param predictions the raw predictions (may be empty; never {@code null})
     */
    record AutocompleteResponse(List<Prediction> predictions) {
        public AutocompleteResponse {
            predictions = predictions == null ? List.of() : predictions;
        }
    }

    /**
     * Raw Google Places prediction.
     *
     * @param description the human-readable prediction text
     * @param placeId     the Google {@code place_id}
     */
    record Prediction(String description, String placeId) {}

    /**
     * Raw Google Places Details response: a single result.
     *
     * @param result the raw details result, or {@code null} when the place was not found
     */
    record DetailsResponse(Result result) {

        /**
         * Raw Google Places details result.
         *
         * @param formattedAddress the canonical formatted address
         * @param latitude         the geometry latitude
         * @param longitude        the geometry longitude
         * @param addressComponents the raw address components (may be empty; never {@code null})
         */
        public record Result(
                String formattedAddress,
                Double latitude,
                Double longitude,
                List<AddressComponent> addressComponents) {
            public Result {
                addressComponents = addressComponents == null ? List.of() : addressComponents;
            }
        }

        /**
         * Raw Google Places address component.
         *
         * @param longName  the full text description
         * @param shortName the abbreviated text
         * @param types     the component types (may be empty; never {@code null})
         */
        public record AddressComponent(String longName, String shortName, List<String> types) {
            public AddressComponent {
                types = types == null ? List.of() : types;
            }
        }
    }
}
