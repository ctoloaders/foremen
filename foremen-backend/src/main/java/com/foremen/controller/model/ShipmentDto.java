package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.EtaSource;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ShipmentSource;
import com.foremen.dao.model.ShipmentStatus;

/**
 * FOR-11-04 (R16.2, R16.3): the shipment read model. Carries no money field by construction (a reflection
 * test guards it). {@code note} is {@code null} for a foreman (R16.4). {@code refreshResult} is set only by
 * the refresh endpoint ({@code UPDATED}, {@code UNCHANGED}, {@code FAILED}).
 */
public record ShipmentDto(
        Long id,
        Long projectId,
        String projectName,
        Long externalOrderId,
        String externalOrderReference,
        DeliveryMethod deliveryMethod,
        String carrierCode,
        String trackingNumber,
        String trackingUrl,
        String shopOrderLink,
        ShipmentStatus status,
        ShipmentSource statusSource,
        LocalDate etaDate,
        LocalTime etaTimeFrom,
        LocalTime etaTimeTo,
        EtaSource etaSource,
        String driverName,
        String driverPhone,
        String note,
        OffsetDateTime shippedAt,
        OffsetDateTime deliveredAt,
        OffsetDateTime lastEventAt,
        OffsetDateTime lastCheckedAt,
        boolean degraded,
        boolean trackingNumberSuspect,
        String trackingPageUrl,
        boolean refreshAvailable,
        String refreshResult,
        List<Line> lines,
        List<Event> events) {

    /** One line: a position and the quantity of it on the shipment. */
    public record Line(Long positionId, String materialLabel, String unitName, BigDecimal quantity,
                       PositionStatus positionStatus) {
    }

    /** One history entry, latest first (capped at 50). */
    public record Event(ShipmentStatus status, OffsetDateTime occurredAt, ShipmentSource source, String note) {
    }

    public ShipmentDto withRefreshResult(String result) {
        return new ShipmentDto(id, projectId, projectName, externalOrderId, externalOrderReference,
                deliveryMethod, carrierCode, trackingNumber, trackingUrl, shopOrderLink, status, statusSource,
                etaDate, etaTimeFrom, etaTimeTo, etaSource, driverName, driverPhone, note, shippedAt,
                deliveredAt, lastEventAt, lastCheckedAt, degraded, trackingNumberSuspect, trackingPageUrl,
                refreshAvailable, result, lines, events);
    }
}
