package com.foremen.controller.model;

/**
 * Create response for a {@code WorkVolumeFormula} row: echoes the work item and its
 * human-readable {@code sourceText}. Mapped from {@code WorkVolumeFormulaServiceExtendedModel}.
 */
public record WorkVolumeFormulaCreateResponse(Long workItemId, String sourceText) {}
