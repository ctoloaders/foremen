package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service-layer list model for a {@code WorkPrice} aggregator (one per work item).
 *
 * <p>Per-package effective prices are flattened into {@link #prices}, keyed by offer package
 * {@code code}. Each entry carries the package id, the currency code, and the effective net price.
 * Unpriced packages omit their key. The map is assembled by the service mapper (task 6.2) via the
 * effective-price resolver.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkPriceServiceModel {
    private Long id;
    private Long workItemId;
    private String workItemName;
    private Map<String, PackagePrice> prices = new LinkedHashMap<>();

    /**
     * A single effective per-package price entry in {@link WorkPriceServiceModel#prices}.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackagePrice {
        private Long offerPackageId;
        private String currencyCode;
        private BigDecimal netPrice;
    }
}
