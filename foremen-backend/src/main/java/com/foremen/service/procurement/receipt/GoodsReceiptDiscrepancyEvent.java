package com.foremen.service.procurement.receipt;

/**
 * FOR-11-05 (Requirement 12.1) - published after a goods-receipt row that is {@code MISSING}, {@code RETURNED},
 * or an {@code ACCEPTED} row that leaves a surplus. Consumed AFTER_COMMIT by
 * {@link GoodsReceiptNotificationEmitter}.
 *
 * @param positionId   purchase position the receipt row belongs to
 * @param projectId    owning project (member lookup)
 * @param receiptId    the receipt row that produced the discrepancy
 * @param actorUserId  the user who recorded the receipt (excluded from recipients)
 */
public record GoodsReceiptDiscrepancyEvent(Long positionId, Long projectId, Long receiptId, Long actorUserId) {
}
