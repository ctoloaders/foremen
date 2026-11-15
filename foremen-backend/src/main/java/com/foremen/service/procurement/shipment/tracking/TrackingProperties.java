package com.foremen.service.procurement.shipment.tracking;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * FOR-11-04 (design section 5): {@code foremen.procurement.tracking.*}. Booleans and durations carry
 * real defaults (an empty environment value would break binding); the shipped state is disabled.
 */
@ConfigurationProperties(prefix = "foremen.procurement.tracking")
public record TrackingProperties(
        boolean enabled,
        String cron,
        String zone,
        int batchSize,
        Duration minShipmentInterval,
        Duration minCallSpacing,
        Duration refreshCooldown,
        Duration carrierPause,
        Duration backoffCap,
        Duration connectTimeout,
        Duration readTimeout,
        long maxResponseBytes,
        Map<String, Carrier> carriers,
        Stub stub) {

    /** Per-carrier flag, bound as a JavaBean so a map of carriers binds from nested YAML. */
    public static class Carrier {
        private boolean enabled;

        public Carrier() {
        }

        public Carrier(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean enabled() {
            return enabled;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public record Stub(boolean enabled) {
    }

    public TrackingProperties {
        cron = blankTo(cron, "-");
        zone = blankTo(zone, "Europe/Warsaw");
        batchSize = batchSize <= 0 ? 200 : batchSize;
        minShipmentInterval = positiveOr(minShipmentInterval, Duration.ofMinutes(30));
        minCallSpacing = positiveOr(minCallSpacing, Duration.ofSeconds(2));
        refreshCooldown = positiveOr(refreshCooldown, Duration.ofSeconds(60));
        carrierPause = positiveOr(carrierPause, Duration.ofHours(1));
        backoffCap = positiveOr(backoffCap, Duration.ofHours(24));
        connectTimeout = positiveOr(connectTimeout, Duration.ofSeconds(5));
        readTimeout = positiveOr(readTimeout, Duration.ofSeconds(10));
        maxResponseBytes = maxResponseBytes <= 0 ? 1_048_576L : maxResponseBytes;
        carriers = carriers == null ? Map.of() : Map.copyOf(carriers);
        stub = stub == null ? new Stub(false) : stub;
    }

    /** {@code true} when the master switch and the per-carrier flag are both on. */
    public boolean isCarrierEnabled(String code) {
        if (!enabled || code == null) {
            return false;
        }
        Carrier carrier = carriers.get(code.toLowerCase(Locale.ROOT));
        return carrier != null && carrier.enabled();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
