package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create payload for a material producer. {@code image} is an optional ImageStorage object key
 * (the bucket-relative GCS key, not a CDN URL; FOR-04-17, Requirement 8.4).
 */
public record MaterialProducerCreateRequest(
    @NotBlank String code,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    Boolean active,
    @Size(max = 512) String image
) {}
