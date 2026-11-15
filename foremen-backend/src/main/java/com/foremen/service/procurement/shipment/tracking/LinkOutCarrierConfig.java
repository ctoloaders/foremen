package com.foremen.service.procurement.shipment.tracking;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FOR-11-04 (R8.2 to R8.5): link-out only registry entries, verdict {@code MANUAL_ONLY} in
 * {@code carrier-spike.md}. No fetch, no per-carrier property.
 *
 * <p>DHL, UPS and FedEx have free official APIs, but their credentialed adapters ({@code DhlAdapter},
 * {@code UpsAdapter}, {@code FedexAdapter}) are NOT built: they need API credentials that the product owner
 * has not registered yet (carrier-spike.md, decision 3). Until then they stay link-out only. When the owner
 * supplies credentials, replace the matching bean here with a real adapter bean of the same code (the registry
 * rejects duplicate codes) and add its flag to {@code application.yml}.
 */
@Configuration
public class LinkOutCarrierConfig {

    @Bean
    public CarrierTrackingAdapter orlenPaczkaLinkOut() {
        return new LinkOutTrackingAdapter("ORLEN_PACZKA", "\\d{7}|\\d{10}|[23]\\d{12}",
                "https://www.orlenpaczka.pl/sledz-paczke/?numer={n}");
    }

    @Bean
    public CarrierTrackingAdapter pocztaPolskaLinkOut() {
        return new LinkOutTrackingAdapter("POCZTA_POLSKA", "\\d{20}|[A-Z]{2}\\d{9}[A-Z]{2}",
                "https://emonitoring.poczta-polska.pl/?numer={n}");
    }

    @Bean
    public CarrierTrackingAdapter dpdLinkOut() {
        return new LinkOutTrackingAdapter("DPD", "[A-Z0-9]{9,20}",
                "https://tracktrace.dpd.com.pl/parcelDetails?typ=1&p1={n}");
    }

    @Bean
    public CarrierTrackingAdapter glsLinkOut() {
        return new LinkOutTrackingAdapter("GLS", "[0-9]{8,14}",
                "https://gls-group.com/PL/pl/sledzenie-paczek/?match={n}");
    }

    @Bean
    public CarrierTrackingAdapter dhlLinkOut() {
        return new LinkOutTrackingAdapter("DHL", "\\d{10}|\\d{12}|\\d{20}|JJD\\d{18,}|JVGL\\d{10,}",
                "https://www.dhl.com/pl-pl/home/tracking.html?tracking-id={n}");
    }

    @Bean
    public CarrierTrackingAdapter upsLinkOut() {
        return new LinkOutTrackingAdapter("UPS", "1Z[0-9A-Z]{16}",
                "https://www.ups.com/track?loc=pl_PL&tracknum={n}");
    }

    @Bean
    public CarrierTrackingAdapter fedexLinkOut() {
        return new LinkOutTrackingAdapter("FEDEX", "\\d{12}|\\d{15}|\\d{20}|\\d{22}|\\d{34}",
                "https://www.fedex.com/fedextrack/?trknbr={n}");
    }
}
