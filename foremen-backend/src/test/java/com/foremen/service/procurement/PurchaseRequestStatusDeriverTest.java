package com.foremen.service.procurement;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.RequestStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static com.foremen.dao.model.PositionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class PurchaseRequestStatusDeriverTest {

    private static RequestStatus d(PositionStatus... s) {
        return PurchaseRequestStatusDeriver.derive(List.of(s));
    }

    @Test
    void derivesStatuses() {
        assertThat(d()).isEqualTo(RequestStatus.OPEN);
        assertThat(d(CANCELLED, CANCELLED)).isEqualTo(RequestStatus.CANCELLED);
        assertThat(d(RECEIVED, CANCELLED)).isEqualTo(RequestStatus.COMPLETED);
        assertThat(d(RECEIVED, RECEIVED)).isEqualTo(RequestStatus.COMPLETED);
        assertThat(d(WITH_BUYER, RECEIVED, CANCELLED)).isEqualTo(RequestStatus.RELEASED);
        assertThat(d(WITH_BUYER, CLIENT_PAID)).isEqualTo(RequestStatus.OPEN);
        assertThat(d(PLANNED, CANCELLED)).isEqualTo(RequestStatus.OPEN);
    }

    @Test
    void overdueUsesWarsawDate() {
        // 22:30 UTC on 2025-06-10 is already 2025-06-11 00:30 in Warsaw (CEST)
        Clock clock = Clock.fixed(Instant.parse("2025-06-10T22:30:00Z"), ZoneOffset.UTC);
        PurchaseRequestOverdue o = new PurchaseRequestOverdue(clock);
        assertThat(o.today()).isEqualTo(LocalDate.of(2025, 6, 11));
        assertThat(o.isOverdue(LocalDate.of(2025, 6, 10), RequestStatus.OPEN)).isTrue();
        assertThat(o.isOverdue(LocalDate.of(2025, 6, 11), RequestStatus.OPEN)).isFalse();
        assertThat(o.isOverdue(LocalDate.of(2025, 6, 10), RequestStatus.COMPLETED)).isFalse();
        assertThat(o.isOverdue(LocalDate.of(2025, 6, 10), RequestStatus.CANCELLED)).isFalse();
        assertThat(o.isOverdue(null, RequestStatus.OPEN)).isFalse();
    }
}
