package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.ShipmentStatus;

/**
 * FOR-11-04 (R4.1, R5.1): body of create and update. {@code projectId} is required on create and ignored
 * on update. Update is a partial update: any field left {@code null} keeps the stored value; an empty string
 * clears a text field (carrier, tracking, link, driver, phone, note). The merged state is validated.
 * {@code etaTimeFrom} / {@code etaTimeTo} are applied only together with a changed {@code etaDate}.
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
