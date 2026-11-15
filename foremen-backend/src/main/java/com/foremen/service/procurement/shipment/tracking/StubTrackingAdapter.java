package com.foremen.service.procurement.shipment.tracking;

import com.foremen.dao.model.ShipmentStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/**
 * FOR-11-04 (R25.6): deterministic carrier {@code STUB} for dockerized scenarios, registered only when
 * {@code foremen.procurement.tracking.stub.enabled=true}. The tracking-number prefix scripts the result:
 * {@code STUBTRANSIT*} in transit with ETA today+2, {@code STUBLATE*} in transit with ETA today+9,
 * {@code STUBDELIV*} delivered, {@code STUBFAIL*} timeout, {@code STUBBLOCK*} blocked; any other
 * {@code STUB*} number stays {@code CREATED}. Performs no network call.
 */
@Component
@ConditionalOnProperty(prefix = "foremen.procurement.tracking.stub", name = "enabled", havingValue = "true")
public class StubTrackingAdapter implements CarrierTrackingAdapter {

    public static final String CODE = "STUB";

    private final Clock clock;
    private final ZoneId zone;

    @Autowired
    public StubTrackingAdapter(TrackingProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public StubTrackingAdapter(TrackingProperties properties, Clock clock) {
        this.clock = clock;
        this.zone = ZoneId.of(properties.zone());
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean matches(String number) {
        return number != null && number.toUpperCase(Locale.ROOT).startsWith("STUB");
    }

    @Override
    public String trackingPageUrl(String number) {
        return "https://tracking.stub.invalid/track/" + TrackingHttpClient.encode(number);
    }

    @Override
    public TrackingResult fetch(String number) throws TrackingUnavailableException {
        String n = number == null ? "" : number.toUpperCase(Locale.ROOT);
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (n.startsWith("STUBFAIL")) {
            throw new TrackingUnavailableException(TrackingFailure.TIMEOUT, "Scripted stub timeout");
        }
        if (n.startsWith("STUBBLOCK")) {
            throw new TrackingUnavailableException(TrackingFailure.BLOCKED, "Scripted stub block");
        }
        if (n.startsWith("STUBDELIV")) {
            return new TrackingResult(ShipmentStatus.DELIVERED, now, null, "Delivered (stub)");
        }
        if (n.startsWith("STUBLATE")) {
            return new TrackingResult(ShipmentStatus.IN_TRANSIT, now, today.plusDays(9), "In transit, delayed (stub)");
        }
        if (n.startsWith("STUBTRANSIT")) {
            return new TrackingResult(ShipmentStatus.IN_TRANSIT, now, today.plusDays(2), "In transit (stub)");
        }
        return new TrackingResult(ShipmentStatus.CREATED, null, null, null);
    }
}
