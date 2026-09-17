package com.foremen.controller.model;

import java.util.Map;

/**
 * Row DTO for the work-prices catalog. The row unit is a {@code WorkPrice} (work item aggregator).
 *
 * <p>The per-package effective prices are flattened into {@code prices}, a map keyed by offer package
 * {@code code} (e.g. {@code budget}/START, {@code norm}/COMFORT, {@code lux}/PRESTIGE). The frontend
 * renders one pivot column per seeded package from this map. Unpriced packages omit their key.
 */
public record WorkPriceDtoModel(Long id, Long workItemId, String workItemName,
                                Map<String, PackagePriceDto> prices) {}
