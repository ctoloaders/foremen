package com.foremen.config.offer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the offer configuration properties so the {@code foremen.offer.escalation} namespace is
 * bound at application startup (FOR-05-07, Requirement 6.4).
 *
 * <p>{@link OfferEscalationProperties} carries the GLOBAL default {@code Escalation_Threshold}
 * (percent and/or absolute cap). The {@link com.foremen.service.EscalationPolicy} consumes these
 * properties, falling back to them when a project has no per-project override on
 * {@link com.foremen.dao.model.OfferProjectSettingsEntity}.
 */
@Configuration
@EnableConfigurationProperties(OfferEscalationProperties.class)
public class OfferConfig {
}
