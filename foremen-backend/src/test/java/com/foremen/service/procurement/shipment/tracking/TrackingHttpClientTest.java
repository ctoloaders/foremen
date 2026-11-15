package com.foremen.service.procurement.shipment.tracking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackingHttpClientTest {

    @Test
    void pinsHttpsAndHost() {
        assertThat(TrackingHttpClient.pinned("Tracking.Example.com", "/t/ABC").toString())
                .isEqualTo("https://tracking.example.com/t/ABC");
    }

    @Test
    void rejectsHostsThatCouldRedirectTheRequest() {
        for (String host : new String[]{"evil.com/x", "a@b.com", "host:8080", "", null, "a b"}) {
            assertThatThrownBy(() -> TrackingHttpClient.pinned(host, "/x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> TrackingHttpClient.pinned("a.com", "//evil.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrackingHttpClient.pinned("a.com", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodesNumbers() {
        assertThat(TrackingHttpClient.encode("A B/1?&")).isEqualTo("A%20B%2F1%3F%26");
    }

    @Test
    void invalidEndpointBecomesOtherFailureWithoutNetwork() {
        var client = new TrackingHttpClient(StubTrackingAdapterTest.properties());
        assertThatThrownBy(() -> client.get("bad host", "/x", null))
                .isInstanceOfSatisfying(TrackingUnavailableException.class,
                        e -> assertThat(e.getFailure()).isEqualTo(TrackingFailure.OTHER));
    }
}
