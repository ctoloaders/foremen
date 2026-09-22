package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Row DTO for the work-prices catalog. The row unit is a {@code WorkPrice}: a work item's single
 * catalog price (FOR-05-04, Requirement 1) — no per-package pivot.
 */
public record WorkPriceDtoModel(Long id, Long workItemId, String workItemName,
                                Long currencyId, String currencyCode, BigDecimal netPrice) {}
