package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * FOR-11-06 (task 2.3): one materials proforma line, summarised. Carries no position, material or
 * purchase-side identifier, so it is safe for the client DTO as well.
 */
public record ClientPaymentLineSummaryDto(
        Integer lineNo,
        String description,
        String sku,
        BigDecimal quantity,
        String unitName,
        BigDecimal net,
        BigDecimal gross) {
}
