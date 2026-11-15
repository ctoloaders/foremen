package com.foremen.service.procurement.shipment.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.ShipmentStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * FOR-11-04 (R8.2 to R8.5, R7.3): InPost through the unauthenticated ShipX endpoint
 * {@code GET https://api-shipx-pl.easypack24.net/v1/tracking/{number}}. Provisional verdict {@code ADAPTER}
 * (carrier-spike.md): shipped disabled ({@code carriers.inpost.enabled=false}) until the product owner accepts
 * the terms risk and a real redacted response is recorded. The parser follows the documented shape
 * ({@code status}, {@code tracking_details[]} with {@code status}, {@code origin_status}, {@code datetime});
 * the success shape was never observed, so the test fixture is synthetic.
 */
@Component
public class InPostAdapter implements CarrierTrackingAdapter {

    public static final String CODE = "INPOST";
    static final String HOST = "api-shipx-pl.easypack24.net";
    private static final Pattern NUMBER = Pattern.compile("^\\d{20,26}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TrackingHttpClient http;
    private final TrackingProperties properties;

    @Autowired
    public InPostAdapter(TrackingHttpClient http, TrackingProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String code() {
        return CODE;
    }

    /** Per-carrier flag only; the master switch is applied by the registry. */
    @Override
    public boolean isEnabled() {
        TrackingProperties.Carrier carrier = properties.carriers().get("inpost");
        return carrier != null && carrier.enabled();
    }

    @Override
    public boolean matches(String number) {
        return number != null && NUMBER.matcher(number.strip()).matches();
    }

    @Override
    public String trackingPageUrl(String number) {
        return "https://inpost.pl/sledzenie-przesylek?number=" + TrackingHttpClient.encode(number);
    }

    @Override
    public TrackingResult fetch(String number) throws TrackingUnavailableException {
        if (number == null || number.isBlank()) {
            throw new TrackingUnavailableException(TrackingFailure.NOT_FOUND, "Empty tracking number");
        }
        String body = http.get(HOST, "/v1/tracking/" + TrackingHttpClient.encode(number.strip()),
                "application/json").body();
        return parse(body);
    }

    /** Pure parse of a ShipX tracking body; {@code PARSE_ERROR} when it is not the expected shape. */
    static TrackingResult parse(String json) throws TrackingUnavailableException {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new TrackingUnavailableException(TrackingFailure.PARSE_ERROR, "Unreadable InPost response");
        }
        if (root == null || !root.isObject()) {
            throw new TrackingUnavailableException(TrackingFailure.PARSE_ERROR, "Unexpected InPost response");
        }
        JsonNode details = root.get("tracking_details");
        JsonNode latest = null;
        Instant latestAt = null;
        if (details != null && details.isArray()) {
            for (JsonNode d : details) {
                Instant at = parseInstant(text(d, "datetime"));
                if (latest == null || (at != null && (latestAt == null || !at.isBefore(latestAt)))) {
                    latest = d;
                    latestAt = at != null ? at : latestAt;
                }
            }
        }
        String statusCode = text(root, "status");
        if (statusCode == null && latest != null) {
            statusCode = text(latest, "status");
        }
        if (statusCode == null) {
            throw new TrackingUnavailableException(TrackingFailure.PARSE_ERROR, "InPost response has no status");
        }
        String eventText = latest == null ? null : firstNonBlank(text(latest, "origin_status"), text(latest, "status"));
        return new TrackingResult(mapStatus(statusCode), latestAt, null, eventText);
    }

    /** Maps an InPost status code to the generic set; unknown codes become {@code UNKNOWN}. */
    static ShipmentStatus mapStatus(String code) {
        return switch (code.strip().toLowerCase(Locale.ROOT)) {
            case "created", "offers_prepared", "offer_selected", "confirmed", "dispatched_by_sender",
                 "dispatched_by_sender_to_pok", "ready_to_pickup_from_pok" -> ShipmentStatus.CREATED;
            case "collected_from_sender", "taken_by_courier", "taken_by_courier_from_pok", "adopted_at_source_branch",
                 "sent_from_source_branch", "adopted_at_sorting_center", "sent_from_sorting_center",
                 "adopted_at_target_branch", "sent_to_customer_service_point", "ready_to_pickup_from_branch",
                 "stack_in_customer_service_point", "stack_in_box_machine" -> ShipmentStatus.IN_TRANSIT;
            case "out_for_delivery", "out_for_delivery_to_address", "ready_to_pickup", "avizo",
                 "pickup_reminder_sent", "pickup_reminder_sent_address" -> ShipmentStatus.OUT_FOR_DELIVERY;
            case "delivered" -> ShipmentStatus.DELIVERED;
            case "undelivered", "rejected_by_receiver", "pickup_time_expired", "returned_to_sender",
                 "claimed", "canceled", "cancelled", "missing", "stack_parcel_pickup_time_expired",
                 "redirect_to_box", "delay_in_delivery", "oversized" -> ShipmentStatus.EXCEPTION;
            default -> ShipmentStatus.UNKNOWN;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || !v.isValueNode() || v.asText().isBlank() ? null : v.asText();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
