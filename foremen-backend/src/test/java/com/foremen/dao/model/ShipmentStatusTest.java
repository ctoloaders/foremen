package com.foremen.dao.model;

import org.junit.jupiter.api.Test;

import static com.foremen.dao.model.ShipmentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class ShipmentStatusTest {

    @Test
    void onlyDeliveredAndCancelledAreTerminal() {
        for (ShipmentStatus s : values()) {
            assertThat(s.isTerminal()).isEqualTo(s == DELIVERED || s == CANCELLED);
        }
    }

    @Test
    void rankOrdersForwardProgress() {
        assertThat(CREATED.rank()).isLessThan(IN_TRANSIT.rank());
        assertThat(IN_TRANSIT.rank()).isLessThan(OUT_FOR_DELIVERY.rank());
        assertThat(OUT_FOR_DELIVERY.rank()).isLessThan(DELIVERED.rank());
    }

    @Test
    void exceptionUnknownAndCancelledHaveNoRank() {
        assertThat(EXCEPTION.isRanked()).isFalse();
        assertThat(UNKNOWN.isRanked()).isFalse();
        assertThat(CANCELLED.isRanked()).isFalse();
        assertThat(CREATED.isRanked()).isTrue();
    }
}
