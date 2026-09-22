package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Create response for a {@code WorkPrice} row: echoes the work item and its single
 * {@code (currency, netPrice)} catalog price. Mapped from {@code WorkPriceServiceExtendedModel}.
 */
public record WorkPriceCreateResponse(Long workItemId, Long currencyId, BigDecimal netPrice) {}
