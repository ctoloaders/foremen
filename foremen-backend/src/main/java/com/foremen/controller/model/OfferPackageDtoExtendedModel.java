package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Edit-form DTO for an {@code OfferPackage}. {@code zlM2} is the read-only denormalized package
 * zł/m² cache (FOR-05-04-UI); nullable and never written from the edit form.
 */
public record OfferPackageDtoExtendedModel(Long id, String code, Integer orderNo, String nameRU, String namePL,
                                           boolean active, BigDecimal zlM2) {}
