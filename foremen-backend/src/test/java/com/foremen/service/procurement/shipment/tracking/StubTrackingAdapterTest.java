package com.foremen.service.procurement.shipment.tracking;

import com.foremen.dao.model.ShipmentStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubTrackingAdapterTest extends AbstractCarrierTrackingAdapterContractTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC);
    private final StubTrackingAdapter adapter = new StubTrackingAdapter(properties(), CLOCK);

    static TrackingProperties properties() {
        return new TrackingProperties(true, null, "UTC", 0, null, null, null, null, null, null, null, 0, null,
                new TrackingProperties.Stub(true));
    }

    @Override
    protected CarrierTrackingAdapter adapter() {
        return adapter;
    }

    @Override
    protected String recognisedNumber() {
        return "STUBTRANSIT1";
    }

    @Override
    protected String fetchableNumber() {
        return "STUBTRANSIT1";
    }

    @Test
    void scriptsResultsByPrefix() throws Exception {
        TrackingResult transit = adapter.fetch("STUBTRANSIT9");
        assertThat(transit.status()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(transit.etaDate()).isEqualTo(LocalDate.of(2026, 3, 12));

        assertThat(adapter.fetch("STUBLATE1").etaDate()).isEqualTo(LocalDate.of(2026, 3, 19));
        assertThat(adapter.fetch("STUBDELIV1").status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(adapter.fetch("STUB123").status()).isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    void scriptsFailuresByPrefix() {
        assertThatThrownBy(() -> adapter.fetch("STUBFAIL1"))
                .isInstanceOfSatisfying(TrackingUnavailableException.class,
                        e -> assertThat(e.getFailure()).isEqualTo(TrackingFailure.TIMEOUT));
        assertThatThrownBy(() -> adapter.fetch("STUBBLOCK1"))
                .isInstanceOfSatisfying(TrackingUnavailableException.class,
                        e -> assertThat(e.getFailure()).isEqualTo(TrackingFailure.BLOCKED));
    }
}
