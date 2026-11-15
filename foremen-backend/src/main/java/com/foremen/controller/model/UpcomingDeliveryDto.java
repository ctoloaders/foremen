package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ShipmentStatus;

/**
 * One planned delivery for the foreman (FOR-11-00 R23.4). Carries no Money_Field by construction.
 * {@code materialModel} / {@code producer} describe the actual material when set, else the planned one.
 *
 * <p>FOR-11-04 (R12) appends nullable fields describing the nearest Active_Shipment of the position; they
 * are all {@code null} when the position has none. {@code shipmentCount} is the number of Active_Shipments.
 */
public record UpcomingDeliveryDto(
        Long positionId,
        Long projectId,
        String projectName,
        String materialModel,
        String producer,
        BigDecimal quantity,
        String unitName,
        LocalDate plannedDeliveryDate,
        PositionStatus status,
        boolean overdue,
        Long requestId,
        Long shipmentId,
        ShipmentStatus shipmentStatus,
        DeliveryMethod deliveryMethod,
        String carrierCode,
        LocalDate etaDate,
        LocalTime etaTimeFrom,
        LocalTime etaTimeTo,
        String driverName,
        String driverPhone,
        String trackingPageUrl,
        String shopOrderLink,
        Integer shipmentCount,
        Boolean degraded) {
}
