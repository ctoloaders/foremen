package com.foremen.controller.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foremen.dao.model.AssignmentStatus;

import java.util.List;

/**
 * A single project-team member as exposed in the project list/read DTOs (FOR-04-13 Requirement 3.4,
 * extended by FOR-05-09 Requirement 19). Materialized from the {@code ProjectEntity.members}
 * read-only collection at projection time.
 *
 * <p><b>FOR-05-09 Requirement 19 criterion 8 — admin-staff masking.</b> The Internal_Attributes
 * ({@code workerTypeId} / {@code workerTypeCode} / {@code workerTypeName} / {@code workerTypeActive},
 * {@code nip}, {@code tags}) are visible only to an Internal_Attribute_Viewer (ADMIN + admin-staff
 * MANAGER/FOREMAN/ESTIMATOR/FINANCIER). For a WORKER or CLIENT reader the projection nulls them, and
 * the class-level {@link JsonInclude}({@code NON_NULL}) omits them from the serialized payload
 * entirely rather than emitting {@code null}, applying the same extended admin-staff masking as the
 * Team_API (D12). The {@code assignmentStatus} is deliberately <strong>not</strong> internal and is
 * returned to every reader.
 *
 * @param userId           the member user's id
 * @param userName         the member user's display name
 * @param roleCode         the project-role code (e.g. {@code "CLIENT"}, {@code "FOREMAN"})
 * @param roleName         the localized project-role name
 * @param assignmentStatus the member's Assignment_Status ({@code ACTIVE} / {@code INACTIVE}); never masked
 * @param workerTypeId     <em>internal</em>; the WORKER member's worker-type id, or {@code null}
 * @param workerTypeCode   <em>internal</em>; the WORKER member's worker-type code, or {@code null}
 * @param workerTypeName   <em>internal</em>; the localized worker-type name, or {@code null}
 * @param workerTypeActive <em>internal</em>; whether the worker type is active, or {@code null}
 * @param nip              <em>internal</em>; the WORKER member's NIP, or {@code null}
 * @param tags             <em>internal</em>; the ordered Assignment_Tag list (empty when none)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectMemberSummaryDto(
        Long userId,
        String userName,
        String roleCode,
        String roleName,
        AssignmentStatus assignmentStatus,
        Long workerTypeId,
        String workerTypeCode,
        String workerTypeName,
        Boolean workerTypeActive,
        String nip,
        List<String> tags
) {}
