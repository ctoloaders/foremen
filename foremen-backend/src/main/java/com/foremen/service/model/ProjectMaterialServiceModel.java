package com.foremen.service.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Read-path service model for a Project_Material: the finishing read model plus {@code projectId}. */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ProjectMaterialServiceModel extends FinishingMaterialServiceModel {
    private Long projectId;
}
