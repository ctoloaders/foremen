package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Update payload for a material producer ({@code code} is immutable). {@code image} is an optional
 * ImageStorage object key (the bucket-relative GCS key, not a CDN URL; FOR-04-17, Requirement 8.4).
 */
public record MaterialProducerUpdateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active,
    @Size(max = 512) String image
) {}
