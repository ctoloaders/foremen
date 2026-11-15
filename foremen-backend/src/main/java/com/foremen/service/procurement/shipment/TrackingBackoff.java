package com.foremen.service.procurement.shipment;

import java.time.Duration;
import java.time.Instant;

/** FOR-11-04 (R7.6): exponential back-off for failing carrier tracking. Pure, no Spring. */
public final class TrackingBackoff {

    /** Consecutive failures from which a shipment is flagged degraded. */
    public static final int DEGRADED_THRESHOLD = 5;

    private static final Duration BASE = Duration.ofHours(1);

    private TrackingBackoff() {
    }

    /**
     * Next check time: {@code now + minInterval} without failures, otherwise
     * {@code now + min(cap, 1h * 2^(failures-1))}. Monotonic in {@code failures}.
     */
    public static Instant nextCheck(Instant now, int failures, Duration minInterval, Duration cap) {
        if (failures <= 0) {
            return now.plus(minInterval);
        }
        // 1h * 2^30 already exceeds any sane cap; clamp the exponent to avoid overflow.
        int exponent = Math.min(failures - 1, 30);
        Duration delay = BASE.multipliedBy(1L << exponent);
        return now.plus(delay.compareTo(cap) > 0 ? cap : delay);
    }

    public static boolean isDegraded(int failures) {
        return failures >= DEGRADED_THRESHOLD;
    }
}
