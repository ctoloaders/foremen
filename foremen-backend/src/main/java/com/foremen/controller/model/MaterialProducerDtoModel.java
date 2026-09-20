package com.foremen.controller.model;

/**
 * List/read row for a material producer. Exposes the localized {@code name} plus the resolved
 * {@code imageUrl} (FOR-04-17, Requirement 8.4) built from the stored GCS object key.
 */
public record MaterialProducerDtoModel(Long id, String code, String name, boolean active, String imageUrl) {}
