package com.foremen.service.model;

import com.foremen.dao.model.AssignmentStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Write-path service model for a {@code ProjectMember} (FOR-05-09).
 *
 * <p>This is the generic {@code ServiceExtendedModel} half of the {@code ProjectScopedService}
 * contract. It carries the flat, settable membership fields; the composition rules, the canonical
 * rejection order, the role-equals-Company_Role derivation, worker-type and tag handling, and the
 * assignment-status lifecycle are layered on in later tasks. The {@code projectRoleId} is a plain
 * id resolved by the service mapper to a managed {@code RoleEntity} reference.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMemberServiceExtendedModel {
    private Long id;
    private Long userId;
    private Long projectId;
    private Long projectRoleId;
    private AssignmentStatus assignmentStatus;
    private Long workerTypeId;
    private List<String> tags;
}
