package com.foremen.controller.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.WorkerKind;
import com.foremen.service.team.TeamBlock;
import com.foremen.service.team.TeamMemberOrdering;

import java.util.List;

/**
 * The enriched {@code Team_Member_View} returned by the Team_API list, assign, and Attribute_Update
 * endpoints (FOR-05-09 Requirement 4). It carries everything the UI needs to render a member without
 * extra lookups: the membership identity, the (immutable) Project_Role equal to the user's
 * Company_Role (D2), the derived {@link TeamBlock}, the visible Assignment_Status, the user record
 * fields, the WORKERS-block worker attributes, and the Internal_Attributes.
 *
 * <p><strong>Internal-attribute masking (D12).</strong> The fields marked <em>internal</em>
 * ({@code workerTypeId}, {@code workerTypeCode}, {@code workerTypeName}, {@code workerTypeActive},
 * {@code nip}, {@code workerTypeMissing}, {@code tags}) are visible only to an
 * Internal_Attribute_Viewer (ADMIN + admin-staff). For a WORKER or CLIENT reader the service nulls
 * them, and the class-level {@link JsonInclude}({@code NON_NULL}) omits them from the serialized
 * payload entirely rather than emitting {@code null} (Requirement 4.10, 4.11). {@code assignmentStatus}
 * is deliberately <strong>not</strong> internal and is returned to every reader.
 *
 * <p>This model never carries a rate, tariff, tier percentage, cost, password, token, or OTP
 * (Requirement 4.7).
 *
 * <p>Implements {@link TeamMemberOrdering.Orderable} via its {@link #companyRoleCode()},
 * {@link #userName()}, and {@link #id()} accessors so a list of views can be sorted directly by
 * {@link TeamMemberOrdering#comparator()} (Requirement 4.6).
 *
 * @param id                membership id ({@code project_members.id})
 * @param userId            the member user's id
 * @param projectId         the owning project id
 * @param projectRoleId     the Project_Role id ({@code == } the user's Company_Role, D2)
 * @param projectRoleCode   the Project_Role code ({@code == companyRoleCode}, D2)
 * @param projectRoleName   the localized Project_Role name (ru for ru requests, pl otherwise, falling
 *                          back to the code — Requirement 24.4)
 * @param companyRoleCode   the user's Company_Role code (equal to {@code projectRoleCode} by D2)
 * @param block             the derived presentation block (ADMIN_STAFF / WORKERS / CLIENTS, D3/D6)
 * @param assignmentStatus  ACTIVE / INACTIVE — visible to every reader (never masked)
 * @param userName          the member user's display name
 * @param userEmail         the member user's email ({@code null}/empty for an uninvited WORKER record)
 * @param userStatus        the member user's account status
 * @param userActive        whether the member user's account is active
 * @param workerKind        WORKERS block only: {@code PERSON} / {@code COMPANY} ({@code null} treated as PERSON)
 * @param contactPerson     WORKERS/COMPANY only: the contact person
 * @param workerTypeId      <em>internal</em>; WORKERS only; {@code null} for an Uncategorized_Worker
 * @param workerTypeCode    <em>internal</em>; WORKERS only; {@code null} for an Uncategorized_Worker
 * @param workerTypeName    <em>internal</em>; WORKERS only localized worker-type name; {@code null} when none
 * @param workerTypeActive  <em>internal</em>; WORKERS only; whether the referenced worker type is active
 * @param nip               <em>internal</em>; WORKERS/COMPANY only; {@code null}/empty when none
 * @param workerTypeMissing <em>internal</em>; {@code true} iff block is WORKERS and no worker type (Requirement 14.16)
 * @param tags              <em>internal</em>; the ordered, normalized tag list (empty when none)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamMemberView(
        Long id,
        Long userId,
        Long projectId,
        Long projectRoleId,
        String projectRoleCode,
        String projectRoleName,
        String companyRoleCode,
        TeamBlock block,
        AssignmentStatus assignmentStatus,
        String userName,
        String userEmail,
        String userStatus,
        Boolean userActive,
        WorkerKind workerKind,
        String contactPerson,
        Long workerTypeId,
        String workerTypeCode,
        String workerTypeName,
        Boolean workerTypeActive,
        String nip,
        Boolean workerTypeMissing,
        List<String> tags
) implements TeamMemberOrdering.Orderable {
}
