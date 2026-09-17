package com.foremen.service.model;

import com.foremen.controller.model.PackagePriceUpsert;

import java.util.List;

/**
 * Service-layer write model for a {@code WorkPrice} aggregator: the work item plus the collection of
 * per-package prices to upsert. Mirrors the extended/create/update controller DTOs.
 */
public record WorkPriceServiceExtendedModel(Long workItemId, List<PackagePriceUpsert> packagePrices) {
}
