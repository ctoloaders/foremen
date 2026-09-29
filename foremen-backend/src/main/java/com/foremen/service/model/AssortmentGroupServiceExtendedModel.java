package com.foremen.service.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Service-layer write model for an {@code AssortmentGroup} row (FOR-05-04, Requirement 6.1).
 * Carries the raw i18n fields ({@code nameRU}/{@code namePL}) the edit form pre-selects, plus
 * {@code sortOrder} and the group's single {@code referenceQty}/{@code referenceUnit}
 * (FOR-05-04-UI), and — FOR-05-05 Amendment A1, point B — the FULL desired set of applicable
 * room-type ids ({@code roomTypeIds}; null/empty = none). The service mapper REPLACES the group's
 * {@code roomTypes} association from this set on create and update.
 */
public record AssortmentGroupServiceExtendedModel(Long id, String nameRU, String namePL, Integer sortOrder,
                                                  BigDecimal referenceQty, String referenceUnit,
                                                  List<Long> roomTypeIds) {
}
