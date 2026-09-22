package com.foremen.controller.model;

/**
 * Create response for a {@code WorkPackageOverride} row: echoes the work item, offer package,
 * membership flag, and override source text. Mapped from
 * {@code WorkPackageOverrideServiceExtendedModel}.
 */
public record WorkPackageOverrideCreateResponse(Long workItemId, Long offerPackageId,
                                                Boolean member, String overrideSourceText) {}
