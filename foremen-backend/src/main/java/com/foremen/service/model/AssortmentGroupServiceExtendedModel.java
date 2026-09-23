package com.foremen.service.model;

import java.math.BigDecimal;

/**
 * Service-layer write model for an {@code AssortmentGroup} row (FOR-05-04, Requirement 6.1).
 * Carries the raw i18n fields ({@code nameRU}/{@code namePL}) the edit form pre-selects, plus
 * {@code sortOrder} and the group's single {@code referenceQty}/{@code referenceUnit}
 * (FOR-05-04-UI).
 */
public record AssortmentGroupServiceExtendedModel(Long id, String nameRU, String namePL, Integer sortOrder,
                                                  BigDecimal referenceQty, String referenceUnit) {
}
