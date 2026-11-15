package com.foremen.service.procurement.shipment.tracking;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CarrierTrackingRegistryTest {

    private static TrackingProperties props(boolean enabled) {
        return new TrackingProperties(enabled, null, null, 0, null, null, null, null, null, null, null, 0,
                Map.of(), null);
    }

    @Test
    void findResolvesEnabledAdapterCaseInsensitively() {
        var registry = new CarrierTrackingRegistry(List.of(new FakeAdapter("DPD", true)), props(true));
        assertThat(registry.find("dpd")).isPresent();
        assertThat(registry.availableCodes()).containsExactly("DPD");
    }

    @Test
    void unknownDisabledOrMasterOffMeansManualOnly() {
        var on = new CarrierTrackingRegistry(List.of(new FakeAdapter("DPD", false)), props(true));
        assertThat(on.find("DPD")).isEmpty();
        assertThat(on.find("OTHER")).isEmpty();
        assertThat(on.find(null)).isEmpty();
        assertThat(on.findAny("DPD")).isPresent();

        var off = new CarrierTrackingRegistry(List.of(new FakeAdapter("DPD", true)), props(false));
        assertThat(off.find("DPD")).isEmpty();
        assertThat(off.availableCodes()).isEmpty();
    }

    @Test
    void duplicateCodesFailStartup() {
        assertThatThrownBy(() -> new CarrierTrackingRegistry(
                List.of(new FakeAdapter("DPD", true), new FakeAdapter("dpd", true)), props(true)))
                .isInstanceOf(IllegalStateException.class);
    }

    private record FakeAdapter(String code, boolean enabled) implements CarrierTrackingAdapter {
        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public boolean matches(String number) {
            return true;
        }

        @Override
        public String trackingPageUrl(String number) {
            return "https://x.invalid/" + number;
        }

        @Override
        public TrackingResult fetch(String number) {
            throw new UnsupportedOperationException();
        }
    }
}
