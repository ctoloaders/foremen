package com.foremen.controller.model;

/**
 * Row DTO for a {@code (WorkItem, OfferPackage)} package membership + override formula pair
 * (FOR-05-04, Requirement 4). Carries no price (Requirement 4.5). The derived
 * {@code overrideParsedAst} is an internal server-side field, never surfaced raw on this DTO.
 */
public record WorkPackageOverrideDtoModel(Long id, Long workItemId, String workItemName,
                                          Long offerPackageId, String offerPackageName,
                                          Boolean member, String overrideSourceText) {}
