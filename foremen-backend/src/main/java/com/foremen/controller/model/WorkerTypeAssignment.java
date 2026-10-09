package com.foremen.controller.model;

import com.foremen.dao.model.AssignmentStatus;

/**
 * The downstream worker-type read model (FOR-05-09 Requirement 14 criterion 13): one entry per
 * WORKER Project_Member of a project, carrying the member's user id, membership id,
 * Assignment_Status, and current Worker_Type id and code.
 *
 * <p>This is a <strong>read-only input</strong> for downstream consumers — FOR-05-06 margin
 * computation and FOR-10 / FOR-11 payroll and real-cost computation — produced by
 * {@code ProjectMemberService.listWorkerTypeAssignments(projectId)}. It is deliberately
 * <em>not</em> a Team_API REST endpoint (the design's REST surface defines no such path); it is a
 * service method consumed in-process by those specs. The read modifies no {@code project_members}
 * row.
 *
 * <p><strong>Inactive Worker_Types are included.</strong> When a member's Worker_Type has since been
 * deactivated through FOR-05-06, its {@code workerTypeId} and {@code workerTypeCode} are still
 * reported (the type is kept on the member, Requirement 14 criterion 10); the consumer sees the
 * reference and can resolve the (inactive) tier itself.
 *
 * <p><strong>Uncategorized_Worker.</strong> A WORKER member with no Worker_Type is reported with an
 * explicit {@code null} {@code workerTypeId} and {@code workerTypeCode}, so a downstream consumer can
 * flag such a member (e.g. treat it as not-ready). Unlike the masked {@code Team_Member_View}, this
 * model is not subject to the admin-staff masking: it is an internal cross-spec contract, never
 * serialized to a WORKER / CLIENT reader.
 *
 * <p>The model carries no rate, tier percentage, cost, NIP, tag, password, or token: computing pay,
 * rates, or costs is out of this spec's scope (Requirement 14 criterion 13).
 *
 * @param userId           the WORKER member's user id
 * @param membershipId     the {@code project_members.id} of the WORKER membership
 * @param assignmentStatus the membership Assignment_Status ({@code ACTIVE} / {@code INACTIVE})
 * @param workerTypeId     the current Worker_Type id, including an inactive type; {@code null} for an
 *                         Uncategorized_Worker
 * @param workerTypeCode   the current Worker_Type code, including an inactive type; {@code null} for
 *                         an Uncategorized_Worker
 */
public record WorkerTypeAssignment(
        Long userId,
        Long membershipId,
        AssignmentStatus assignmentStatus,
        Long workerTypeId,
        String workerTypeCode
) {}
