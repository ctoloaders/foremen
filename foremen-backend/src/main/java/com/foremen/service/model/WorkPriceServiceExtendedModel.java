package com.foremen.service.model;

import java.math.BigDecimal;

/**
 * Service-layer write model for a {@code WorkPrice} row: the work item plus its single
 * {@code (currency, netPrice)} catalog price (FOR-05-04, Requirement 1). Mirrors the
 * extended/create/update controller DTOs.
 */
public record WorkPriceServiceExtendedModel(Long workItemId, Long currencyId, BigDecimal netPrice) {
}
