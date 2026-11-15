package com.foremen.controller.model;

/**
 * FOR-11-05 (R5.5): result of a void. {@code redeliveryKept} is true when a re-delivery shipment of the
 * event exists but could not be cancelled and stays for the buyer.
 */
public record GoodsReceiptVoidResult(GoodsReceiptDto receipt, PositionLedgerDto position,
                                     boolean redeliveryKept) {
}
