package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Service-layer read model for a {@code WorkVolumeFormula} row: a work item's optional default
 * volume formula (FOR-05-04, Requirement 2). Exposes only the human-readable {@code sourceText} —
 * the parsed/validated {@code parsedAst} is an internal derived field used by the formula
 * evaluator and is never surfaced raw on this DTO (mirrors the "derived field never exposed as
 * client-facing state" convention already used for {@code WorkPriceServiceModel}'s collapsed
 * fields).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkVolumeFormulaServiceModel {
    private Long id;
    private Long workItemId;
    private String workItemName;
    private String sourceText;
}
