package com.foremen.service.estimateai;

import java.math.BigDecimal;

import com.foremen.service.estimate.LabourPriceAdjustmentScope;

/**
 * FOR-05-04: a labour price coefficient stated by the user in the prompt. {@code targetId} is the resolved
 * category/work item id, {@code null} for {@code PROJECT}.
 */
public record PromptCoefficient(LabourPriceAdjustmentScope scope, Long targetId, BigDecimal pct, String reason) {
}
