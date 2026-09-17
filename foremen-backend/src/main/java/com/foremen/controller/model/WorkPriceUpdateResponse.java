package com.foremen.controller.model;

import java.util.List;

/**
 * Update response for a {@code WorkPrice} aggregator: echoes the work item and the collection of
 * per-package prices that were upserted. Mapped from {@code WorkPriceServiceExtendedModel}.
 */
public record WorkPriceUpdateResponse(Long workItemId, List<PackagePriceUpsert> packagePrices) {}
