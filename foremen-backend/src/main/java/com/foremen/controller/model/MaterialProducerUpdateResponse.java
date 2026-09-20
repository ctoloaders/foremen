package com.foremen.controller.model;

/**
 * Response returned after updating a material producer. Exposes the resolved {@code imageUrl}
 * (FOR-04-17, Requirement 8.4) built from the stored GCS object key.
 */
public record MaterialProducerUpdateResponse(Long id, String code, String nameRU, String namePL, boolean active,
                                             String imageUrl) {}
