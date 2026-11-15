package com.foremen.service.procurement.shipment.tracking;

import com.foremen.dao.model.ShipmentStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FOR-11-04 (R8.3, R7.3): offline tests over a SYNTHETIC fixture; no carrier is ever called. */
class InPostAdapterTest extends AbstractCarrierTrackingAdapterContractTest {

    private static final String NUMBER = "000000000000000000000000";

    private final String delivered = resource("/tracking/inpost-delivered.synthetic.json");

    /** Returns the fixture instead of calling the network. */
    private final TrackingHttpClient fakeHttp = new TrackingHttpClient(properties(false)) {
        @Override
        public Response get(String host, String pathAndQuery, String accept) {
            assertThat(host).isEqualTo("api-shipx-pl.easypack24.net");
            assertThat(pathAndQuery).isEqualTo("/v1/tracking/" + NUMBER);
            return new Response(200, delivered);
        }
    };

    private final InPostAdapter adapter = new InPostAdapter(fakeHttp, properties(false));

    static TrackingProperties properties(boolean inpostEnabled) {
        return new TrackingProperties(true, null, null, 0, null, null, null, null, null, null, null, 0,
                Map.of("inpost", new TrackingProperties.Carrier(inpostEnabled)), null);
    }

    private static String resource(String path) {
        try (var in = InPostAdapterTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected CarrierTrackingAdapter adapter() {
        return adapter;
    }

    @Override
    protected String recognisedNumber() {
        return NUMBER;
    }

    @Override
    protected String fetchableNumber() {
        return NUMBER;
    }

    @Test
    void disabledByDefaultAndEnabledByFlag() {
        assertThat(adapter.isEnabled()).isFalse();
        assertThat(new InPostAdapter(fakeHttp, properties(true)).isEnabled()).isTrue();
        assertThat(new InPostAdapter(fakeHttp, new TrackingProperties(true, null, null, 0, null, null, null, null,
                null, null, null, 0, null, null)).isEnabled()).isFalse();
    }

    @Test
    void parsesDeliveredFixture() throws Exception {
        TrackingResult r = adapter.fetch(NUMBER);
        assertThat(r.status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(r.lastEventAt()).isEqualTo(Instant.parse("2026-03-10T17:45:00Z"));
        assertThat(r.eventText()).isEqualTo("Delivered");
        assertThat(r.etaDate()).isNull();
    }

    @Test
    void fallsBackToLatestDetailWhenTopLevelStatusMissing() throws Exception {
        String json = """
                {"tracking_details":[
                  {"status":"out_for_delivery","datetime":"2026-03-10T08:00:00+01:00"},
                  {"status":"created","datetime":"2026-03-09T08:00:00+01:00"}]}""";
        assertThat(InPostAdapter.parse(json).status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void mapsStatuses() {
        assertThat(InPostAdapter.mapStatus("created")).isEqualTo(ShipmentStatus.CREATED);
        assertThat(InPostAdapter.mapStatus("adopted_at_sorting_center")).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(InPostAdapter.mapStatus("READY_TO_PICKUP")).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(InPostAdapter.mapStatus("delivered")).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(InPostAdapter.mapStatus("returned_to_sender")).isEqualTo(ShipmentStatus.EXCEPTION);
        assertThat(InPostAdapter.mapStatus("something_new")).isEqualTo(ShipmentStatus.UNKNOWN);
    }

    @Test
    void malformedBodiesAreParseErrors() {
        for (String body : new String[]{"not json", "[]", "{}", resource("/tracking/inpost-not-found.json")}) {
            assertThatThrownBy(() -> InPostAdapter.parse(body))
                    .isInstanceOfSatisfying(TrackingUnavailableException.class,
                            e -> assertThat(e.getFailure()).isEqualTo(TrackingFailure.PARSE_ERROR));
        }
    }

    @Test
    void softNumberCheck() {
        assertThat(adapter.matches("123456789012345678901234")).isTrue();
        assertThat(adapter.matches("1234")).isFalse();
        assertThat(adapter.matches("12345678901234567890ABCD")).isFalse();
    }
}
