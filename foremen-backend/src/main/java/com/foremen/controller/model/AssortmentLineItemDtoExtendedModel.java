package com.foremen.controller.model;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Edit-form DTO for an {@code AssortmentLineItem} (FOR-05-04, Requirement 6.1, 6.2). Carries the
 * raw i18n fields, the flat FK ids the write path resolves, and the price/quantity fields.
 * {@code typicalProductId} is nullable and assistive/provenance only (Requirement 6.6, 6.7).
 */
public record AssortmentLineItemDtoExtendedModel(
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
