package com.foremen.config.offer;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * GLOBAL default for the offer {@code Escalation_Threshold} (FOR-05-07, Requirement 6.4), bound from
 * the {@code foremen.offer.escalation} namespace in {@code application.yml}.
 *
 * <p>The threshold caps the value a MANAGER may propose on a {@code MANAGER_PROPOSAL} without ADMIN
 * approval (Requirements 6.2/6.3): a {@code PERCENT} proposal is gated against {@link #percentCap}
 * and an {@code ABSOLUTE} proposal against {@link #absoluteCap}. This holder carries ONLY the GLOBAL
 * default; a per-project override lives on
 * {@link com.foremen.dao.model.OfferProjectSettingsEntity} and, when present, takes precedence
 * (resolved by {@link com.foremen.service.EscalationPolicy}).
 *
 * <p>A {@code null} cap means "no cap for that kind" — no {@code MANAGER_PROPOSAL} of that kind ever
 * requires ADMIN approval. Both caps are validated {@code @PositiveOrZero} so a misconfigured
 * negative value aborts context startup with a clear configuration error.
 */
@Validated
@ConfigurationProperties(prefix = "foremen.offer.escalation")
public record OfferEscalationProperties(
        @PositiveOrZero BigDecimal percentCap,
        @PositiveOrZero BigDecimal absoluteCap) {
}
