package com.foremen.controller.model;

import java.util.List;

/**
 * FOR-11-05 (R4.1, R4.5): result of recording a batch. {@code receipts} are all rows of the request (split
 * rows included), {@code positions} carry the new ledger and status per position, and
 * {@code redeliveryShipmentIds} lists the system re-delivery shipments the request created.
 */
public record GoodsReceiptBatchResult(List<GoodsReceiptDto> receipts, List<PositionLedgerDto> positions,
                                      List<Long> redeliveryShipmentIds) {
}
