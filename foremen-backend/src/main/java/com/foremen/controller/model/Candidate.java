package com.foremen.controller.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foremen.dao.model.WorkerKind;
import com.foremen.service.team.TeamBlock;

/**
 * One result of the Team_API candidate lookup
 * ({@code GET /api/project-members/candidates}, FOR-05-09 Requirement 11). A candidate is a user who
 * may be assigned to the project: not already a member (in any Assignment_Status), not an
 * Inactive_User, and compatible with the requested role/block.
 *
 * <p>The model exposes only what the add/invite dialogs need to pick a person. It never carries a
 * password, token, rate, cost, NIP, or tag (Requirement 11.5). The optional WORKERS fields
 * ({@code workerKind}, {@code contactPerson}, and — FOR-02-07 team preselect — the user's user-level
 * {@code workerTypeId}/{@code workerTypeName}) are omitted from the payload when absent via the
 * class-level {@link JsonInclude}({@code NON_NULL}).
 *
 * <p>FOR-02-07 (team worker-type preselect): the WORKERS candidate additionally carries the user's
 * OWN (user-level) Worker_Type — a tier REFERENCE (id + localized name), NOT a cost/rate/margin —
 * so the add-member dialog can preselect the per-project worker-type selector from the chosen
 * worker's default. This is a deliberate, product-approved relaxation of the original Req 11.5
 * "no worker type on a candidate" note; the selector itself is already gated on {@code WORKER_TYPES}
 * READ (D9), so exposing the tier reference discloses nothing a reader of the catalog cannot see.
 *
 * @param userId          the candidate user's id
 * @param name            the candidate user's display name
 * @param email           the candidate user's email ({@code null}/empty for an uninvited WORKER record)
 * @param status          the candidate user's account status
 * @param companyRoleCode the candidate user's Company_Role code
 * @param companyRoleName the localized Company_Role name
 * @param block           the block the candidate would join (derived from Company_Role, D3/D6)
 * @param workerKind      WORKERS block only: {@code PERSON} / {@code COMPANY}; omitted otherwise
 * @param contactPerson   WORKERS/COMPANY only: the contact person; omitted otherwise
 * @param workerTypeId    WORKERS block only: the user's user-level Worker_Type id (preselect); omitted otherwise
 * @param workerTypeName  WORKERS block only: that Worker_Type's localized name; omitted otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Candidate(
        Long userId,
        String name,
        String email,
        String status,
        String companyRoleCode,
        String companyRoleName,
        TeamBlock block,
        WorkerKind workerKind,
        String contactPerson,
        Long workerTypeId,
        String workerTypeName
) {}
