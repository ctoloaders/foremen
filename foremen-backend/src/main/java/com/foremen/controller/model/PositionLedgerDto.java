package com.foremen.controller.model;

import com.foremen.dao.model.PositionStatus;

/**
 * FOR-11-05 (R9.4): ledger of a position with its current status. Also the per-position entry of
 * {@link GoodsReceiptBatchResult} and {@link GoodsReceiptVoidResult}.
 */
public record PositionLedgerDto(Long positionId, PositionStatus positionStatus, LedgerDto ledger) {
}
