package com.foremen.service.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Write-path service model for a Project_Material (FOR-11-00 R3). A finishing-material write model
 * plus the owning {@code projectId} (required on create, immutable afterwards) and the optional
 * {@code sellerId}. Extending the finishing model lets the existing finishing write validation run
 * unchanged on it.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ProjectMaterialServiceExtendedModel extends FinishingMaterialServiceExtendedModel {
    private Long projectId;
    private Long sellerId;
}
