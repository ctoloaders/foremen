package com.foremen.service.model;

/**
 * Write-path service model for a material producer. Carries {@code code}/{@code nameRU}/
 * {@code namePL}/{@code active} plus the optional {@code image} ImageStorage object key
 * (FOR-04-17, Requirement 8.4) — the raw GCS key on write, never the resolved CDN URL.
 */
public record MaterialProducerServiceExtendedModel(Long id, String code, String nameRU, String namePL,
                                                   boolean active, String image) {
}
