package com.foremen.controller.model;

/**
 * A single Google Places autocomplete prediction, normalized for the frontend (FOR-04-13,
 * Requirement 5.1).
 *
 * <p>Carries only the human-readable {@code description} and the Google {@code place_id}
 * ({@code placeId}); the server-side Google Places API key is never part of this shape
 * (Requirement 5.3).
 *
 * @param description the human-readable prediction text
 * @param placeId     the Google-assigned place identifier ({@code place_id})
 */
public record PlacePredictionDto(String description, String placeId) {}
