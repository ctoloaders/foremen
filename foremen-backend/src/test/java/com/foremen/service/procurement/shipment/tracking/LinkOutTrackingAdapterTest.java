package com.foremen.service.procurement.shipment.tracking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FOR-11-04 (R8.2, R8.4): link-out only registry entries. */
class LinkOutTrackingAdapterTest extends AbstractCarrierTrackingAdapterContractTest {

    private final LinkOutCarrierConfig config = new LinkOutCarrierConfig();
    private final CarrierTrackingAdapter adapter = config.dpdLinkOut();

    @Override
    protected CarrierTrackingAdapter adapter() {
        return adapter;
    }

    @Override
    protected String recognisedNumber() {
        return "ABC123456789";
    }

    @Test
    void everyLinkOutCarrierIsRegisteredButNeverEnabledOrFetched() {
        List<CarrierTrackingAdapter> all = List.of(config.orlenPaczkaLinkOut(), config.pocztaPolskaLinkOut(),
                config.dpdLinkOut(), config.glsLinkOut(), config.dhlLinkOut(), config.upsLinkOut(),
                config.fedexLinkOut());
        var props = InPostAdapterTest.properties(true);
        var registry = new CarrierTrackingRegistry(all, props);

        assertThat(all).extracting(CarrierTrackingAdapter::code).containsExactly("ORLEN_PACZKA",
                "POCZTA_POLSKA", "DPD", "GLS", "DHL", "UPS", "FEDEX");
        assertThat(registry.availableCodes()).isEmpty();
        for (CarrierTrackingAdapter a : all) {
            assertThat(registry.find(a.code())).isEmpty();
            assertThat(registry.findAny(a.code())).isPresent();
            assertThat(a.trackingPageUrl("X 1")).startsWith("https://").contains("X%201");
            assertThatThrownBy(() -> a.fetch("X")).isInstanceOfSatisfying(TrackingUnavailableException.class,
                    e -> assertThat(e.getFailure()).isEqualTo(TrackingFailure.OTHER));
        }
    }

    @Test
    void numberPatternsAndLinks() {
        assertThat(config.orlenPaczkaLinkOut().matches("2123456789012")).isTrue();
        assertThat(config.orlenPaczkaLinkOut().matches("4123456789012")).isFalse();
        assertThat(config.upsLinkOut().matches("1z999aa10123456784")).isTrue();
        assertThat(config.pocztaPolskaLinkOut().matches("RR123456789PL")).isTrue();
        assertThat(config.glsLinkOut().matches("1234567")).isFalse();
        assertThat(config.dpdLinkOut().trackingPageUrl("ABC123456789"))
                .isEqualTo("https://tracktrace.dpd.com.pl/parcelDetails?typ=1&p1=ABC123456789");
    }

    @Test
    void registryAcceptsInPostAlongsideLinkOutCarriers() {
        var inpost = new InPostAdapter(new TrackingHttpClient(InPostAdapterTest.properties(true)),
                InPostAdapterTest.properties(true));
        var registry = new CarrierTrackingRegistry(List.of(inpost, config.dpdLinkOut()),
                InPostAdapterTest.properties(true));
        assertThat(registry.availableCodes()).containsExactly("INPOST");
    }
}
