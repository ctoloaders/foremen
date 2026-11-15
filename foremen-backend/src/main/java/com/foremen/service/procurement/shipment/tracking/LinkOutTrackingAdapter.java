package com.foremen.service.procurement.shipment.tracking;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * FOR-11-04 (R8.2, R8.4): registry entry of a carrier without automated tracking ({@code MANUAL_ONLY}).
 * It only builds the public tracking page link and soft-checks the number; it never fetches anything and
 * is never enabled, so {@link CarrierTrackingRegistry#find(String)} resolves it to empty while
 * {@link CarrierTrackingRegistry#findAny(String)} still serves the link.
 */
public class LinkOutTrackingAdapter implements CarrierTrackingAdapter {

    private final String code;
    private final Pattern numberPattern;
    private final String urlTemplate;

    /**
     * @param urlTemplate https URL containing the {@code {n}} placeholder for the URL-encoded number
     */
    public LinkOutTrackingAdapter(String code, String numberRegex, String urlTemplate) {
        if (urlTemplate == null || !urlTemplate.startsWith("https://") || !urlTemplate.contains("{n}")) {
            throw new IllegalArgumentException("Link-out template must be https and contain {n}");
        }
        this.code = code.toUpperCase(Locale.ROOT);
        this.numberPattern = Pattern.compile(numberRegex);
        this.urlTemplate = urlTemplate;
    }

    @Override
    public String code() {
        return code;
    }

    /** Link-out carriers are never polled. */
    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public boolean matches(String number) {
        return number != null && numberPattern.matcher(number.strip().toUpperCase(Locale.ROOT)).matches();
    }

    @Override
    public String trackingPageUrl(String number) {
        return urlTemplate.replace("{n}", TrackingHttpClient.encode(number == null ? "" : number.strip()));
    }

    @Override
    public TrackingResult fetch(String number) throws TrackingUnavailableException {
        throw new TrackingUnavailableException(TrackingFailure.OTHER, "Carrier " + code + " is link-out only");
    }
}
