package com.foremen.controller.model;

/** FOR-11-06 (R12.2): body of {@code POST /api/goods-receipts/delegations}. */
public record GoodsReceiptDelegationRequest(Long shipmentId, Long assigneeId) {
}
