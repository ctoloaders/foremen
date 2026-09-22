package com.foremen.service.model;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Service-layer list model for a {@code WorkPrice} row: the single catalog price for a work item
 * (FOR-05-04, Requirement 1). No per-package pivot — {@code currencyCode}/{@code netPrice} are the
 * work's one price, read directly with no MAX-fallback.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkPriceServiceModel {
    private Long id;
    private Long workItemId;
    private String workItemName;
    private Long currencyId;
    private String currencyCode;
    private BigDecimal netPrice;
}
