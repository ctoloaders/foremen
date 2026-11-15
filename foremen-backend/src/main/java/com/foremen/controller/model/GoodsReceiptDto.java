package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.foremen.dao.model.ReceiptKind;

/**
 * FOR-11-05 (R9.2, R9.5, R7.5): a receipt event. No money and no storage keys; media carries metadata only.
 * {@code canVoid} and {@code canEditMedia} are computed for the caller.
 */
public record GoodsReceiptDto(
        Long id,
        Long projectId,
        Long positionId,
        String materialLabel,
        String unitName,
        Long shipmentId,
        ReceiptKind kind,
        BigDecimal quantity,
        boolean fromAccepted,
        String comment,
        OffsetDateTime occurredAt,
        Long receivedById,
        String receivedByName,
        OffsetDateTime voidedAt,
        String voidedByName,
        String voidReason,
        List<GoodsReceiptMediaDto> media,
        boolean canVoid,
        boolean canEditMedia) {
}
