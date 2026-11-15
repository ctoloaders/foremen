package com.foremen.controller.model;

import java.util.List;

/** FOR-11-05 (R4.1): body of {@code POST /api/goods-receipts}; the whole batch is one transaction. */
public record GoodsReceiptRequest(List<GoodsReceiptEventRequest> events) {
}
