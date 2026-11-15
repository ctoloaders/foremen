package com.foremen.service.procurement.shipment.tracking;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CarrierThrottleTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-03-10T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final CarrierThrottle throttle = new CarrierThrottle(
            new TrackingProperties(true, null, null, 0, null, Duration.ofSeconds(2), null, Duration.ofHours(1),
                    null, null, null, 0, null, null), clock);

    @Test
    void enforcesCallSpacingPerCarrier() {
        assertThat(throttle.tryAcquire("DPD")).isTrue();
        assertThat(throttle.tryAcquire("dpd")).isFalse();
        assertThat(throttle.tryAcquire("GLS")).isTrue();
        clock.advance(Duration.ofSeconds(2));
        assertThat(throttle.tryAcquire("DPD")).isTrue();
    }

    @Test
    void pauseLastsForTheCooldownOnly() {
        assertThat(throttle.isPaused("DPD")).isFalse();
        throttle.pause("DPD");
        assertThat(throttle.isPaused("DPD")).isTrue();
        assertThat(throttle.isPaused("GLS")).isFalse();
        clock.advance(Duration.ofHours(1));
        assertThat(throttle.isPaused("DPD")).isFalse();
    }
}
