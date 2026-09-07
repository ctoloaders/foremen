package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Normalized Google Places details result for a resolved {@code placeId} (FOR-04-13,
 * Requirement 5.2).
 *
 * <p>Exposes the canonical {@code formattedAddress}, the geometry ({@code latitude}/
 * {@code longitude}), and, where available, minimal address {@code components}. The server-side
 * Google Places API key is never part of this shape (Requirement 5.3).
 *
 * @param formattedAddress the canonical Google-formatted address string
 * @param latitude         the resolved latitude, or {@code null} when unavailable
 * @param longitude        the resolved longitude, or {@code null} when unavailable
 * @param components        minimal address components (may be empty, never {@code null})
 */
public record PlaceDetailsDto(
        String formattedAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        List<PlaceComponentDto> components) {

    /**
     * A single Google Places address component.
     *
     * @param longName  the full text description of the component
     * @param shortName the abbreviated text of the component (falls back to {@code longName})
     * @param types     the component types reported by Google (may be empty)
     */
    public record PlaceComponentDto(String longName, String shortName, List<String> types) {}
}
