package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Row DTO for an {@code OfferPackage}. {@code zlM2} is the read-only denormalized package zł/m²
 * cache (FOR-05-04-UI); nullable.
 */
public record OfferPackageDtoModel(Long id, String code, Integer orderNo, String name, boolean active,
                                   BigDecimal zlM2) {}
