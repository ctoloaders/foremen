package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ShipmentStatus;

/** FOR-11-05 (R9.3): a position awaiting receipt with its ledger and shipments. No money. */
public record ToReceiveDto(
        Long positionId,
        Long projectId,
        String materialLabel,
        String unitName,
        PositionStatus positionStatus,
        LedgerDto ledger,
        List<Shipment> shipments) {

    /** A non-cancelled shipment line of the position; {@code unresolvedQuantity} is not yet received. */
    public record Shipment(Long shipmentId, ShipmentStatus status, LocalDate etaDate, String carrierCode,
                           BigDecimal quantity, BigDecimal unresolvedQuantity) {
    }
}
