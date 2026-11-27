package com.foremen.service.estimateai;

import java.math.BigDecimal;

/**
 * FOR-05-04 (Requirements 5.3, 9.3) — a work the model needs that cannot be priced from the catalog.
 *
 * @param reason one of {@link WarningCodes#UNKNOWN_CODE}, {@link WarningCodes#NO_PRICE},
 *               {@link WarningCodes#UNSUPPORTED_UNIT}, {@link WarningCodes#NOT_IN_CATALOG}
 */
public record Unpriced(String name, String unit, BigDecimal estimatedVolume, String reason, String source,
                       String workCode) {
}
