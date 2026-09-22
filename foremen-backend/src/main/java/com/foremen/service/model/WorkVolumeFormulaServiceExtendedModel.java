package com.foremen.service.model;

/**
 * Service-layer write model for a {@code WorkVolumeFormula} row (FOR-05-04, Requirement 2).
 *
 * <p>Carries only {@code workItemId} and the human-readable {@code sourceText}; the validated
 * {@code parsedAst} is <strong>not</strong> a client-settable field — it is derived server-side by
 * running {@code sourceText} through {@code FormulaParser.parse(...)} and
 * {@code FormulaValidator.validate(...)} before persist (Requirement 2.6). That parse+validate
 * wiring is task 18.3's scope (controller/service wiring), not this DTO-layer task; the mapper
 * for this model ignores {@code parsedAst} on the write side accordingly.
 */
public record WorkVolumeFormulaServiceExtendedModel(Long id, Long workItemId, String sourceText) {
}
