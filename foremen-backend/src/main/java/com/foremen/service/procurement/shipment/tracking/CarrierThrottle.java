package com.foremen.service.procurement.shipment.tracking;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FOR-11-04 (R7.6, R7.7): in-memory per-carrier call spacing and carrier pause. A restart resets it,
 * which is acceptable because {@code next_check_at} is persisted.
 */
@Component
public class CarrierThrottle {

    private final Clock clock;
    private final TrackingProperties properties;
    private final Map<String, Instant> lastCall = new ConcurrentHashMap<>();
    private final Map<String, Instant> pausedUntil = new ConcurrentHashMap<>();

    @Autowired
    public CarrierThrottle(TrackingProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public CarrierThrottle(TrackingProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** {@code true} while the carrier is paused after a {@code RATE_LIMITED} / {@code BLOCKED} failure. */
    public boolean isPaused(String code) {
        Instant until = pausedUntil.get(key(code));
        return until != null && clock.instant().isBefore(until);
    }

    /** Pauses the carrier for the configured cool-down. */
    public void pause(String code) {
        pausedUntil.put(key(code), clock.instant().plus(properties.carrierPause()));
    }

    /**
     * Atomically reserves the next outbound call slot. Returns {@code true} and records the call when
     * the spacing since the previous call has elapsed; {@code false} otherwise (caller skips and retries
     * later).
     */
    public boolean tryAcquire(String code) {
        Instant now = clock.instant();
        Duration spacing = properties.minCallSpacing();
        boolean[] acquired = {false};
        lastCall.compute(key(code), (k, previous) -> {
            if (previous == null || !now.isBefore(previous.plus(spacing))) {
                acquired[0] = true;
                return now;
            }
            return previous;
        });
        return acquired[0];
    }

    private static String key(String code) {
        return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    }
}
