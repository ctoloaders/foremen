package com.foremen.service.model;

/**
 * Service-layer write model for a {@code WorkPackageOverride} row (FOR-05-04, Requirement 4).
 *
 * <p>Carries {@code workItemId}, {@code offerPackageId}, the {@code member} flag, and the
 * human-readable {@code overrideSourceText}; carries no price (Requirement 4.5). The validated
 * {@code overrideParsedAst} is <strong>not</strong> a client-settable field — it is derived
 * server-side by running {@code overrideSourceText} through {@code FormulaParser.parse(...)} and
 * {@code FormulaValidator.validate(...)} before persist (Requirement 4.4). That parse+validate
 * wiring is task 18.3's scope (controller/service wiring), not this DTO-layer task; the mapper
 * for this model ignores {@code overrideParsedAst} on the write side accordingly.
 */
public record WorkPackageOverrideServiceExtendedModel(Long id, Long workItemId, Long offerPackageId,
                                                       Boolean member, String overrideSourceText) {
}
