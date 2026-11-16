package com.foremen.controller.model;

import com.foremen.dao.model.DelegationStatus;

import java.time.OffsetDateTime;

/** FOR-11-06 (R12.2, R12.3): a goods-receipt delegation. Carries no money. */
public record GoodsReceiptDelegationDto(
        Long id,
        Long shipmentId,
        Long projectId,
        Long assigneeId,
        String assigneeName,
        Long delegatedById,
        String delegatedByName,
        OffsetDateTime delegatedAt,
        DelegationStatus status,
        OffsetDateTime revokedAt,
        OffsetDateTime completedAt) {
}
