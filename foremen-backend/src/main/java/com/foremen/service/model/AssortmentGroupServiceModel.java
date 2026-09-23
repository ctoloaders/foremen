package com.foremen.service.model;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Service-layer read model for an {@code AssortmentGroup} row: a curated grouping of
 * finishing/fixture line items used by the package zł/m² pricing model (FOR-05-04,
 * Requirement 6.1). {@code name} is the localized display name (PL fallback, populated by the
 * shared i18n framework from {@code nameRU}/{@code namePL}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssortmentGroupServiceModel {
    private Long id;
    private String name;
    private Integer sortOrder;
    private BigDecimal referenceQty;
    private String referenceUnit;
}
