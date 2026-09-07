package com.foremen.service.google;

import com.foremen.config.security.GooglePlacesProperties;
import com.foremen.controller.model.PlaceDetailsDto;
import com.foremen.controller.model.PlaceDetailsDto.PlaceComponentDto;
import com.foremen.controller.model.PlacePredictionDto;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Normalizes Google Places autocomplete/details results into the public DTO shapes and provides the
 * details-resolution used both by the address proxy controller and by
 * {@code ProjectService.createProject}/{@code update} (FOR-04-13, Requirement 5.1, 5.2, 5.7).
 *
 * <p>The service depends only on the mockable {@link GooglePlacesClient} (Requirement 7.8, 7.9),
 * which injects the server-side API key internally. The service never receives, logs, or echoes the
 * key: its inputs are a free-text query or a {@code placeId}, and its outputs are the key-free
 * {@link PlacePredictionDto} / {@link PlaceDetailsDto} shapes (Requirement 5.3).
 */
@Service
@RequiredArgsConstructor
public class GooglePlacesService {

    private final GooglePlacesClient client;
    private final GooglePlacesProperties properties;

    /**
     * Returns autocomplete predictions for a free-text address query, normalized to
     * {@link PlacePredictionDto} (Requirement 5.1).
     *
     * @param query the free-text address query
     * @return the predictions (never {@code null}; may be empty)
     */
    public List<PlacePredictionDto> autocomplete(String query) {
        if (!properties.enabled()) {
            return List.of();
        }
        return client.autocomplete(query).predictions().stream()
                .map(p -> new PlacePredictionDto(p.description(), p.placeId()))
                .toList();
    }

    /**
     * Resolves the canonical address details for a Google {@code placeId}, normalized to
     * {@link PlaceDetailsDto} (Requirement 5.2). Used by the address proxy and by
     * {@code ProjectService.createProject}/{@code update} for server-side details resolution
     * (Requirement 5.7).
     *
     * @param placeId the Google-assigned {@code place_id}
     * @return the normalized details, or {@code null} when the place has no result
     */
    public PlaceDetailsDto resolveDetails(String placeId) {
        if (!properties.enabled()) {
            return null;
        }
        GooglePlacesClient.DetailsResponse.Result result = client.details(placeId).result();
        if (result == null) {
            return null;
        }

        List<PlaceComponentDto> components = result.addressComponents().stream()
                .map(c -> new PlaceComponentDto(c.longName(), c.shortName(), c.types()))
                .toList();

        return new PlaceDetailsDto(
                result.formattedAddress(),
                toBigDecimal(result.latitude()),
                toBigDecimal(result.longitude()),
                components);
    }

    private static BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
