package com.foremen.controller.model;
import java.math.BigDecimal;
/**
 * FOR-11-06 (R10.5, task 10.4): a materials proforma line as the STAFF read DTO exposes it. On top of the
 * client-safe summary it carries the purchase {@code positionId} (the draft editor removes a line by
 * position id) and the line VAT. Never used in {@code ClientPaymentClientDto}.
 */
public record ClientPaymentStaffLineDto(
        Integer lineNo,
        String description,
        String sku,
        BigDecimal quantity,
        String unitName,
        BigDecimal net,
        BigDecimal vat,
        BigDecimal gross,
        Long positionId) {
}
