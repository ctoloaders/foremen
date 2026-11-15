package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.UUID;

import com.foremen.dao.model.ReceiptKind;

/**
 * FOR-11-05 (R4.1, R4.8): one receipt event of a batch. {@code clientRequestId} makes a retry idempotent.
 * The event time is server time and not part of the request (R4.9).
 */
public record GoodsReceiptEventRequest(
        Long positionId,
        Long shipmentId,
        ReceiptKind kind,
        BigDecimal quantity,
        Boolean fromAccepted,
        String comment,
        UUID clientRequestId) {
}
