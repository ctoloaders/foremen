package com.foremen.service.model;

import com.foremen.dao.model.AssignmentStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Read-path service (list) model for a {@code ProjectMember} (FOR-05-09).
 *
 * <p>This is the generic {@code ServiceModel} half of the {@code ProjectScopedService} contract.
 * It carries the flat membership fields the CRUD framework reads/writes; the enriched, caller-masked
 * {@code Team_Member_View} the Team_API serves (localized role name, derived block, user attributes,
 * {@code workerTypeMissing}, …) is composed in a later task on top of this base. The internal
 * attributes {@code workerTypeId} and {@code tags} are listed in {@code getAdminOnlyFields()} so the
 * existing masking mechanism nulls them for non-admin-staff callers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMemberServiceModel {
    private Long id;
    private Long userId;
    private Long projectId;
    private Long projectRoleId;
    private String projectRoleCode;
    private AssignmentStatus assignmentStatus;
    private Long workerTypeId;
    private List<String> tags;
}
