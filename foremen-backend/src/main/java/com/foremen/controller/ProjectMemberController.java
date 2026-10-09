package com.foremen.controller;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.AssignProjectMemberRequest;
import com.foremen.controller.model.Candidate;
import com.foremen.controller.model.ProjectMemberResponse;
import com.foremen.controller.model.TeamMemberView;
import com.foremen.controller.model.TeamReadiness;
import com.foremen.controller.model.UpdateProjectMemberRequest;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.service.ProjectMemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for managing project memberships. The class carries the ABAC resource via a
 * class-level {@link PermissionResource @PermissionResource("PROJECT_MEMBERS")}; every handler
 * carries a method-level {@link PermissionOperation @PermissionOperation} naming its operation. The
 * {@code PermissionResolver} combines the two into the {@code (resource, operation)} pair enforced
 * at runtime by the {@code PermissionInterceptor} (ADMIN bypass included) and validated at startup
 * by {@code PermissionAnnotationValidator} (entity-creation-rules step 3). The resource reuses the
 * already-seeded project-scoped {@code PROJECT_MEMBERS} row (changeset 015 / 136); no
 * {@code PROJECT_TEAM} resource is referenced (D1).
 *
 * <p>The {@code projectId} is always carried in the request body or as a query parameter — never in
 * the path — so no project identifier ever leaks into the URL (Requirement 2.5, 2.6).
 *
 * <p>Operation mapping: READ for the list and the project-ids read; CREATE for assign; DELETE for
 * remove. The PATCH Attribute_Update (UPDATE), candidate lookup (CREATE), and readiness (READ)
 * handlers are introduced and annotated by their own later tasks.
 *
 * <p>Requirements: 2.1, 2.5, 2.6
 */
@RestController
@RequestMapping("/api/project-members")
@RequiredArgsConstructor
@PermissionResource("PROJECT_MEMBERS")
public class ProjectMemberController {

    private static final String CREATE = "CREATE";
    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    private final ProjectMemberService projectMemberService;

    /**
     * Assigns a user to a project under a project role. Returns 201 Created with the persisted
     * membership. {@code PROJECT_MEMBERS} CREATE.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PermissionOperation(CREATE)
    public ProjectMemberResponse assign(@Valid @RequestBody AssignProjectMemberRequest request) {
        ProjectMemberEntity member = projectMemberService.assign(
                request.userId(), request.projectId(), request.projectRoleId(),
                request.workerTypeId(), request.tags());
        return toResponse(member);
    }

    /**
     * The single Attribute_Update endpoint (FOR-05-09 Requirement 2 criterion 1, design
     * §"Attribute_Update dispatch"). The {@code (userId, projectId)} target and the one attribute
     * group to change travel in the request body — {@code projectId} is never in the path
     * (Requirement 2 criteria 5, 6). The service dispatches on which field is present:
     * {@code assignmentStatus} &rarr; deactivate / reactivate (Requirement 27); {@code workerTypeId}
     * &rarr; worker-type set / replace (Requirement 14, task 9.1); {@code tags} &rarr; tag replace
     * (Requirement 15, task 9.5). Returns 200 with the updated (or, for an idempotent no-op, the
     * current) {@link TeamMemberView}, masked for the caller. {@code PROJECT_MEMBERS} UPDATE.
     */
    @PatchMapping
    @PermissionOperation(UPDATE)
    public ResponseEntity<TeamMemberView> updateAttributes(
            @Valid @RequestBody UpdateProjectMemberRequest request) {
        TeamMemberView view = projectMemberService.updateAttributes(
                request.userId(), request.projectId(), request.assignmentStatus(),
                request.workerTypeId(), request.tags());
        return ResponseEntity.ok(view);
    }

    /**
     * Removes a user's membership on a project, identified by the {@code userId}/{@code projectId}
     * query parameters. Returns 204 No Content with no body. {@code PROJECT_MEMBERS} DELETE.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PermissionOperation(DELETE)
    public void remove(@RequestParam Long userId, @RequestParam Long projectId) {
        projectMemberService.remove(userId, projectId);
    }

    /**
     * Lists every membership on the given project as an ordered, enriched {@link TeamMemberView}
     * list (FOR-05-09 Requirement 4). The service returns one view per member — including INACTIVE,
     * INVITED, and inactive-user members — in the deterministic Team_Block order, an empty list for
     * an empty team, and a 400 for a missing / non-positive {@code projectId}. The localized
     * Project_Role name follows the request locale (ru → Russian, else Polish, falling back to the
     * code). {@code PROJECT_MEMBERS} READ.
     */
    @GetMapping
    @PermissionOperation(READ)
    public ResponseEntity<List<TeamMemberView>> listMembers(@RequestParam Long projectId) {
        return ResponseEntity.ok(projectMemberService.listMemberViews(projectId));
    }

    /**
     * Candidate lookup (FOR-05-09 Requirement 11): a paginated list of users eligible to be assigned
     * to the project — not already a member (any Assignment_Status) and not an Inactive_User —
     * narrowed by the optional {@code term} / {@code role} / {@code block} filters and ordered by
     * display name then id. The {@code projectId} travels as a query parameter, never in the path
     * (Requirement 2 criteria 5, 6). Returns 200 with a {@link Page} of {@link Candidate} carrying
     * the total match count; 400 for an unassignable {@code role}, a bad {@code block}, a negative
     * {@code page}, a {@code size} below one, or a {@code term} longer than 100 characters after
     * trimming; 404 for a non-accessible / non-existent project.
     *
     * <p><b>Operation = CREATE.</b> Candidate lookup is the first step of assigning a member, so it
     * resolves to {@code PROJECT_MEMBERS} CREATE (design, task 5.2): a caller holding only
     * {@code PROJECT_MEMBERS} READ (FOREMAN / ESTIMATOR / FINANCIER) is rejected with 403
     * {@code error.access.denied} by the {@code PermissionInterceptor} before this handler runs
     * (Requirement 11 criterion 10), and WORKER / CLIENT (no grant) likewise. {@code page} defaults
     * to 0 and {@code size} to the service default (20, clamped to 50).
     */
    @GetMapping("/candidates")
    @PermissionOperation(CREATE)
    public ResponseEntity<Page<Candidate>> listCandidates(
            @RequestParam Long projectId,
            @RequestParam(required = false) String term,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String block,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(
                projectMemberService.searchCandidates(projectId, term, role, block, page, size));
    }

    /**
     * Lists the project ids the given user belongs to in any Assignment_Status, in ascending order
     * and restricted to the caller's Accessible_Projects for a non-ADMIN caller (ADMIN bypass). The
     * body is an empty list (not a 404) when the user has no memberships, does not exist, or has no
     * project accessible to the caller (FOR-05-09 Requirement 3.6). {@code PROJECT_MEMBERS} READ.
     */
    @GetMapping("/projects")
    @PermissionOperation(READ)
    public ResponseEntity<List<Long>> listProjects(@RequestParam Long userId) {
        return ResponseEntity.ok(projectMemberService.listProjects(userId));
    }

    /**
     * The {@code team} readiness gate (FOR-05-09 Requirement 20, design §"Readiness"): returns
     * {@code {key:"team", state, counts}} where each count is the number of ACTIVE members of a role
     * on the project (INACTIVE excluded, D14) and {@code state} is {@code DONE} iff the project has at
     * least one ACTIVE {@code FOREMAN}, {@code BLOCKED} otherwise. The {@code projectId} travels as a
     * query parameter, never in the path (Requirement 2 criteria 5, 6). Returns 200 with the gate.
     *
     * <p><b>Operation = READ.</b> A caller holding {@code PROJECT_MEMBERS} READ (ADMIN / MANAGER /
     * FOREMAN / ESTIMATOR / FINANCIER) reaches the gate; WORKER / CLIENT (no grant) are rejected with
     * 403 {@code error.access.denied} by the {@code PermissionInterceptor} before this handler runs
     * (Requirement 20 criterion 9). The service then applies the FOR-03-04 project-scope gate, so a
     * non-accessible / non-existent project yields 404 {@code error.entity.not.found} with an ADMIN
     * bypass (Requirement 20 criterion 10). {@code PROJECT_MEMBERS} READ.
     */
    @GetMapping("/readiness")
    @PermissionOperation(READ)
    public ResponseEntity<TeamReadiness> readiness(@RequestParam Long projectId) {
        return ResponseEntity.ok(projectMemberService.readiness(projectId));
    }

    private ProjectMemberResponse toResponse(ProjectMemberEntity member) {
        return new ProjectMemberResponse(
                member.getId(),
                member.getUser().getId(),
                member.getProjectId(),
                member.getProjectRole().getId(),
                member.getProjectRole().getCode());
    }
}
