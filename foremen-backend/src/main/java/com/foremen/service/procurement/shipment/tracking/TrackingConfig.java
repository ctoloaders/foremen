package com.foremen.service.procurement.shipment.tracking;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** FOR-11-04: binds {@link TrackingProperties} at startup. */
@Configuration
@EnableConfigurationProperties(TrackingProperties.class)
public class TrackingConfig {
}
