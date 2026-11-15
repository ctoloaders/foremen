package com.foremen.service.procurement.shipment;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingBackoffTest {

    private static final Instant NOW = Instant.parse("2026-03-02T08:00:00Z");
    private static final Duration MIN = Duration.ofMinutes(30);
    private static final Duration CAP = Duration.ofHours(24);

    @Test
    void noFailuresUsesMinInterval() {
        assertThat(TrackingBackoff.nextCheck(NOW, 0, MIN, CAP)).isEqualTo(NOW.plus(MIN));
    }

    @Test
    void doublesFromOneHourAndCapsAtCap() {
        assertThat(TrackingBackoff.nextCheck(NOW, 1, MIN, CAP)).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(TrackingBackoff.nextCheck(NOW, 2, MIN, CAP)).isEqualTo(NOW.plus(Duration.ofHours(2)));
        assertThat(TrackingBackoff.nextCheck(NOW, 3, MIN, CAP)).isEqualTo(NOW.plus(Duration.ofHours(4)));
        assertThat(TrackingBackoff.nextCheck(NOW, 6, MIN, CAP)).isEqualTo(NOW.plus(CAP));
        assertThat(TrackingBackoff.nextCheck(NOW, 1000, MIN, CAP)).isEqualTo(NOW.plus(CAP));
    }

    @Test
    void monotonicInFailures() {
        Instant prev = TrackingBackoff.nextCheck(NOW, 1, MIN, CAP);
        for (int f = 2; f < 60; f++) {
            Instant next = TrackingBackoff.nextCheck(NOW, f, MIN, CAP);
            assertThat(next).isAfterOrEqualTo(prev);
            prev = next;
        }
    }

    @Test
    void degradedFromFiveFailures() {
        assertThat(TrackingBackoff.isDegraded(4)).isFalse();
        assertThat(TrackingBackoff.isDegraded(5)).isTrue();
    }
}
