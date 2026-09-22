package com.foremen.controller.model;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AssortmentLineItemCreateRequest(
    @NotNull Long assortmentGroupId,
    @NotNull Long offerPackageId,
    @NotBlank String nameRU,
    @NotBlank String namePL,
    BigDecimal minPrice,
    BigDecimal avgPrice,
    BigDecimal maxPrice,
    @NotNull BigDecimal qtyRef50,
    Long typicalProductId
) {}
