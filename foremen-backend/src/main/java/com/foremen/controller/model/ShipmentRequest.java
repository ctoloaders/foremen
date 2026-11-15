package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.ShipmentStatus;

/**
 * FOR-11-04 (R4.1, R5.1): body of create and update. {@code projectId} is required on create and ignored
 * on update. On update, {@code etaDate} / {@code status} / {@code lines} left {@code null} mean "unchanged";
 * the method, driver and note fields are replaced as given.
 */
public record ShipmentRequest(
        Long projectId,
        Long externalOrderId,
        DeliveryMethod deliveryMethod,
        String carrierCode,
        String trackingNumber,
        String trackingUrl,
        String shopOrderLink,
        LocalDate etaDate,
        LocalTime etaTimeFrom,
        LocalTime etaTimeTo,
        String driverName,
        String driverPhone,
        String note,
        ShipmentStatus status,
        List<Line> lines) {

    /** A position and the quantity of it carried by the shipment. */
    public record Line(Long positionId, BigDecimal quantity) {
    }
}
