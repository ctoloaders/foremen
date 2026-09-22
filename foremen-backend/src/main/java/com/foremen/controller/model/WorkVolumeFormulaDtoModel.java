package com.foremen.controller.model;

/**
 * Row DTO for a work item's optional default volume formula (FOR-05-04, Requirement 2). Exposes
 * only the human-readable {@code sourceText} — the derived {@code parsedAst} is an internal
 * server-side field, never surfaced raw on this DTO.
 */
public record WorkVolumeFormulaDtoModel(Long id, Long workItemId, String workItemName, String sourceText) {}
