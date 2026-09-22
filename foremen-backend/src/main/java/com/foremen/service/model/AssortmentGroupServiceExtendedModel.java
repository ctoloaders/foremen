package com.foremen.service.model;

/**
 * Service-layer write model for an {@code AssortmentGroup} row (FOR-05-04, Requirement 6.1).
 * Carries the raw i18n fields ({@code nameRU}/{@code namePL}) the edit form pre-selects, plus
 * {@code sortOrder}.
 */
public record AssortmentGroupServiceExtendedModel(Long id, String nameRU, String namePL, Integer sortOrder) {
}
