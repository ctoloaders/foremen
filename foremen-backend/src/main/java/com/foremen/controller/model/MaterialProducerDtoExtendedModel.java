package com.foremen.controller.model;

/**
 * Extended read DTO for a material producer. Exposes the raw {@code nameRU}/{@code namePL} plus the
 * resolved {@code imageUrl} (FOR-04-17, Requirement 8.4) built from the stored GCS object key.
 */
public record MaterialProducerDtoExtendedModel(Long id, String code, String nameRU, String namePL, boolean active,
                                               String imageUrl) {}
