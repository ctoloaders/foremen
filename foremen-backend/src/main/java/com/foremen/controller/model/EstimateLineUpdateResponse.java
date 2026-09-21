package com.foremen.controller.model;

import java.math.BigDecimal;

/** Outbound update response for {@code EstimateLine} (FOR-05-03, Requirement 2). */
public record EstimateLineUpdateResponse(
        Long id,
        Long estimateId,
        Long workItemId,
        Long workPriceId,
        Long unitId,
        Integer lineNo,
        String comment,
        BigDecimal unitPrice,
        BigDecimal quantity,
        BigDecimal valueNet
) {}
