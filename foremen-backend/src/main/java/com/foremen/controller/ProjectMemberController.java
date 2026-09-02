package com.foremen.controller;

import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.AssignProjectMemberRequest;
import com.foremen.controller.model.ProjectMemberResponse;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.service.ProjectMemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * REST endpoints for managing project memberships. Every mutation and read is guarded by
 * {@code @RequiresPermission} on the {@code PROJECT_MEMBERS} resource and enforced at runtime by the
 * {@code PermissionInterceptor} (ADMIN bypass included).
 *
 * <p>The {@code projectId} is always carried in the request body or as a query parameter — never in
 * the path — so no project identifier ever leaks into the URL (Requirement 10.1, 10.2).
 *
 * <p>Requirements: 10.1, 10.2, 10.6, 10.9, 10.11, 10.12
 */
@RestController
@RequestMapping("/api/project-members")
@RequiredArgsConstructor
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;

    /**
     * Assigns a user to a project under a project role. Returns 201 Created with the persisted
     * membership (Requirement 10.6).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = "PROJECT_MEMBERS", operation = "CREATE")
    public ProjectMemberResponse assign(@Valid @RequestBody AssignProjectMemberRequest request) {
        ProjectMemberEntity member = projectMemberService.assign(
                request.userId(), request.projectId(), request.projectRoleId());
        return toResponse(member);
    }

    /**
     * Removes a user's membership on a project, identified by the {@code userId}/{@code projectId}
     * query parameters. Returns 204 No Content with no body (Requirement 10.9).
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(resource = "PROJECT_MEMBERS", operation = "DELETE")
    public void remove(@RequestParam Long userId, @RequestParam Long projectId) {
        projectMemberService.remove(userId, projectId);
    }

    /** Lists every membership on the given project (Requirement 10.11). */
    @GetMapping
    @RequiresPermission(resource = "PROJECT_MEMBERS", operation = "READ")
    public ResponseEntity<List<ProjectMemberResponse>> listMembers(@RequestParam Long projectId) {
        List<ProjectMemberResponse> members = projectMemberService.listMembers(projectId).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(members);
    }

    /** Lists the distinct set of project ids the given user is a member of (Requirement 10.12). */
    @GetMapping("/projects")
    @RequiresPermission(resource = "PROJECT_MEMBERS", operation = "READ")
    public ResponseEntity<Set<Long>> listProjects(@RequestParam Long userId) {
        return ResponseEntity.ok(projectMemberService.listProjects(userId));
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
