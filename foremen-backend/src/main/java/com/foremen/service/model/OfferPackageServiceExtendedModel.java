package com.foremen.service.model;

import java.math.BigDecimal;

/**
 * Write/edit-form model for an {@code OfferPackage}. {@code zlM2} is read-only on reads
 * (FOR-05-04-UI) and is never a write source — the create/update mappers ignore it so a
 * package edit never clobbers the persisted zł/m² cache.
 */
public record OfferPackageServiceExtendedModel(Long id, String code, Integer orderNo, String nameRU, String namePL,
                                               boolean active, BigDecimal zlM2) {
}
