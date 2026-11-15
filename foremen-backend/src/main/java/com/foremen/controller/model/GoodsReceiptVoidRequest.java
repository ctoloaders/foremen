package com.foremen.controller.model;

/** FOR-11-05 (R5.1): body of {@code POST /api/goods-receipts/{id}/void}; the reason is mandatory. */
public record GoodsReceiptVoidRequest(String reason) {
}
