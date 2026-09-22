package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Service-layer read model for a {@code WorkPackageOverride} row: a {@code (WorkItem,
 * OfferPackage)} pair's package membership flag and optional package-specific volume formula
 * override (FOR-05-04, Requirement 4). Carries no price (Requirement 4.5) — the work's single
 * catalog price is independent of package membership. Exposes only the human-readable
 * {@code overrideSourceText}; the parsed/validated {@code overrideParsedAst} is an internal
 * derived field never surfaced raw on this DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkPackageOverrideServiceModel {
    private Long id;
    private Long workItemId;
    private String workItemName;
    private Long offerPackageId;
    private String offerPackageName;
    private Boolean member;
    private String overrideSourceText;
}
