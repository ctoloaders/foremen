package com.foremen.controller.model.client;

import java.math.BigDecimal;

/**
 * FOR-11-03 (R6.3): one proforma line as the client sees it. Sale-side values only: no position id,
 * no purchase price, no seller, no delivery cost.
 */
public record ClientProformaLineClientDto(
        int lineNo,
        String name,
        String sku,
        BigDecimal quantity,
        String unit,
        BigDecimal unitNet,
        BigDecimal vatPercent,
        BigDecimal net,
        BigDecimal vat,
        BigDecimal gross) {
}
