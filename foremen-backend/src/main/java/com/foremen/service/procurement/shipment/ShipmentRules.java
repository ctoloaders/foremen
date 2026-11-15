package com.foremen.service.procurement.shipment;

import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ShipmentSource;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** FOR-11-04: pure shipment rules (no Spring context, no persistence). */
public final class ShipmentRules {

    public static final String OTHER_CARRIER = "OTHER";

    private static final Pattern TRACKING_NUMBER = Pattern.compile("[A-Z0-9-]{4,40}");
    private static final Pattern PHONE = Pattern.compile("[0-9 +\\-()]{5,40}");
    private static final int MAX_URL_LENGTH = 1000;

    private static final Set<PositionStatus> AGREED_PACKAGE_SHIPPABLE =
            Set.of(PositionStatus.SUPPLIER_PAID, PositionStatus.IN_DELIVERY, PositionStatus.PARTIALLY_RECEIVED);
    private static final Set<PositionStatus> CLIENT_OWN_SHIPPABLE =
            Set.of(PositionStatus.IN_DELIVERY, PositionStatus.PARTIALLY_RECEIVED);

    private ShipmentRules() {
    }

    /** Method-dependent fields after validation; fields foreign to the method are {@code null} (R3.6). */
    public record MethodFields(String carrierCode, String trackingNumber, String trackingUrl,
                               String shopOrderLink, String driverPhone) {
    }

    /** One ETA of an Active_Shipment of a position. */
    public record EtaCandidate(LocalDate etaDate) {
    }

    /** R3.3: trim, drop spaces, upper-case, validate {@code [A-Z0-9-]{4,40}}. */
    public static String normaliseTrackingNumber(String raw) {
        if (raw == null) {
            throw bad("error.shipment.trackingNumber.invalid", "trackingNumber");
        }
        String n = raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!TRACKING_NUMBER.matcher(n).matches()) {
            throw bad("error.shipment.trackingNumber.invalid", "trackingNumber");
        }
        return n;
    }

    /**
     * R3.1 to R3.6: validates and normalises the method fields.
     *
     * @param knownCarriers codes of the carrier registry; {@code OTHER} is always accepted
     */
    public static MethodFields validateMethod(DeliveryMethod method, String carrierCode, String trackingNumber,
                                              String trackingUrl, String shopOrderLink, String driverPhone,
                                              Set<String> knownCarriers) {
        if (method == null) {
            throw bad("error.shipment.method.required", "deliveryMethod");
        }
        String phone = blankToNull(driverPhone);
        if (phone != null) {
            phone = phone.trim();
            if (!PHONE.matcher(phone).matches()) {
                throw bad("error.shipment.phone.invalid", "driverPhone");
            }
        }
        switch (method) {
            case CARRIER -> {
                String code = blankToNull(carrierCode);
                if (code == null) {
                    throw bad("error.shipment.carrier.required", "carrierCode");
                }
                code = code.trim().toUpperCase(Locale.ROOT);
                if (!OTHER_CARRIER.equals(code) && (knownCarriers == null || !knownCarriers.contains(code))) {
                    throw bad("error.shipment.carrier.unknown", "carrierCode");
                }
                String number = normaliseTrackingNumber(trackingNumber);
                String url = OTHER_CARRIER.equals(code) ? validateUrl(trackingUrl, "trackingUrl") : null;
                return new MethodFields(code, number, url, null, phone);
            }
            case SHOP_DELIVERY -> {
                return new MethodFields(null, null, null, validateUrl(shopOrderLink, "shopOrderLink"), phone);
            }
            case PHONE_CONTACT -> {
                if (phone == null) {
                    throw bad("error.shipment.phone.required", "driverPhone");
                }
                return new MethodFields(null, null, null, null, phone);
            }
            default -> throw bad("error.shipment.method.required", "deliveryMethod");
        }
    }

    /** R3.4: absolute http/https URL up to 1000 characters; blank becomes {@code null}. */
    public static String validateUrl(String raw) {
        return validateUrl(raw, "trackingUrl");
    }

    /** As {@link #validateUrl(String)}; {@code field} names the request field reported on a violation. */
    public static String validateUrl(String raw, String field) {
        String url = blankToNull(raw);
        if (url == null) {
            return null;
        }
        url = url.trim();
        if (url.length() > MAX_URL_LENGTH) {
            throw bad("error.shipment.link.invalid", field);
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw bad("error.shipment.link.invalid", field);
            }
        } catch (IllegalArgumentException e) {
            throw bad("error.shipment.link.invalid", field);
        }
        return url;
    }

    /** R4.4: {@code orderedQuantity} for AGREED / PACKAGE, {@code quantity} for CLIENT_OWN. */
    public static BigDecimal capFor(SourcingPath path, BigDecimal orderedQuantity, BigDecimal quantity) {
        return path == SourcingPath.CLIENT_OWN ? quantity : orderedQuantity;
    }

    /** R4.2: whether a position of this path and status may be put on a shipment. */
    public static boolean canShip(SourcingPath path, PositionStatus status) {
        if (path == null || status == null) {
            return false;
        }
        return path == SourcingPath.CLIENT_OWN
                ? CLIENT_OWN_SHIPPABLE.contains(status)
                : AGREED_PACKAGE_SHIPPABLE.contains(status);
    }

    /**
     * R5.3: may a tracking result replace the current status? A terminal shipment never changes;
     * {@code DELIVERED} always wins; a TRACKING-owned status is always replaced; a MANUAL-owned status
     * only by a forward move (an unranked current status counts as lowest, an unranked incoming never moves forward).
     */
    public static boolean mayOverwriteStatus(ShipmentStatus current, ShipmentSource currentSource,
                                             ShipmentStatus incoming) {
        if (current == null || incoming == null || current.isTerminal()) {
            return false;
        }
        if (incoming == ShipmentStatus.DELIVERED) {
            return true;
        }
        if (currentSource != ShipmentSource.MANUAL) {
            return true;
        }
        return incoming.isRanked() && incoming.rank() > current.rank();
    }

    /**
     * R11.1: the latest ETA among active shipments; with none, the current value is kept when a delivered
     * shipment exists, otherwise the request date (possibly {@code null}).
     */
    public static LocalDate effectiveEta(List<EtaCandidate> candidates, LocalDate requestDate,
                                         boolean hasDelivered, LocalDate current) {
        LocalDate latest = null;
        if (candidates != null) {
            for (EtaCandidate c : candidates) {
                if (c != null && c.etaDate() != null && (latest == null || c.etaDate().isAfter(latest))) {
                    latest = c.etaDate();
                }
            }
        }
        if (latest != null) {
            return latest;
        }
        return hasDelivered ? current : requestDate;
    }

    /** R11.3 / R15.4: both dates known and the new one at least one calendar day later. */
    public static boolean isDelayed(LocalDate oldEta, LocalDate newEta) {
        return oldEta != null && newEta != null && ChronoUnit.DAYS.between(oldEta, newEta) >= 1;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ForemenApiException bad(String key, String field) {
        return new ForemenApiException(HttpStatus.BAD_REQUEST, key).withField(field);
    }
}
