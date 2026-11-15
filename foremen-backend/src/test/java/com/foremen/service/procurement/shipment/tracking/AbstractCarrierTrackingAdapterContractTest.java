package com.foremen.service.procurement.shipment.tracking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * FOR-11-04 (R7.1, R7.5, R8.4): SPI contract every {@link CarrierTrackingAdapter} must satisfy.
 * Subclasses supply the adapter and a number it recognises; adapters that can answer without a network
 * call (stub, fixture-backed) also supply {@link #fetchableNumber()}.
 */
public abstract class AbstractCarrierTrackingAdapterContractTest {

    protected abstract CarrierTrackingAdapter adapter();

    /** A number for which {@code matches} returns true. */
    protected abstract String recognisedNumber();

    /** A number {@code fetch} can resolve offline, or {@code null} to skip the fetch contract. */
    protected String fetchableNumber() {
        return null;
    }

    @Test
    void codeIsNonBlankUpperCase() {
        String code = adapter().code();
        assertThat(code).isNotBlank().isEqualTo(code.strip().toUpperCase());
    }

    @Test
    void matchesRecognisedNumberAndToleratesNullAndEmpty() {
        assertThat(adapter().matches(recognisedNumber())).isTrue();
        assertThat(adapter().matches(null)).isFalse();
        assertThat(adapter().matches("")).isFalse();
    }

    @Test
    void trackingPageUrlIsHttpsAndEncodesTheNumber() {
        String url = adapter().trackingPageUrl("AB 12/3?x=y&z");
        assertThat(url).startsWith("https://");
        assertThat(url).doesNotContain(" ", "?x=y", "&z");
    }

    @Test
    void fetchReturnsBoundedNormalisedResult() throws Exception {
        assumeThat(fetchableNumber()).isNotNull();
        TrackingResult result = adapter().fetch(fetchableNumber());
        assertThat(result.status()).isNotNull();
        if (result.eventText() != null) {
            assertThat(result.eventText().length()).isLessThanOrEqualTo(TrackingResult.MAX_EVENT_TEXT);
        }
    }
}
