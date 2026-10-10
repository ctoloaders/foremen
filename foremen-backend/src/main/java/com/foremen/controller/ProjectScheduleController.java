package com.foremen.controller;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.schedule.AutoCreateRequest;
import com.foremen.controller.model.schedule.SaveBarsRequest;
import com.foremen.controller.model.schedule.ScheduleReadiness;
import com.foremen.controller.model.schedule.ScheduleView;
import com.foremen.service.ProjectScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the planning Gantt (Harmonogram) of a design-stage project, exposed under
 * {@code /api/project-schedules} (FOR-05-10 Requirement 2). The class carries the ABAC resource via
 * a class-level {@link PermissionResource @PermissionResource("WORK_SCHEDULE")}; every handler
 * carries a method-level {@link PermissionOperation @PermissionOperation} naming its operation. The
 * {@code PermissionResolver} combines the two into the {@code (resource, operation)} pair enforced
 * at runtime by the {@code PermissionInterceptor} (ADMIN bypass included) and validated at startup
 * by {@code PermissionAnnotationValidator} — a controller that carried {@code @PermissionResource}
 * but left any in-scope handler without a matching {@code @PermissionOperation} would fail startup
 * (entity-creation-rules step 3). The {@code WORK_SCHEDULE} resource and its grants are seeded by
 * changeset 153.
 *
 * <p>Operation mapping (Requirement 2.2): the Schedule_View read and the readiness read resolve to
 * READ; the bar save and Auto_Create resolve to UPDATE. {@code projectId} always travels as a
 * required query parameter — a missing or non-numeric value yields HTTP 400 (Requirement 3.4),
 * matching {@code ProjectMemberController}. The service applies the project-scope gate (404 for a
 * non-accessible / non-existent project), the lifecycle lock (409), the optimistic-lock version
 * (409), and the business validation of Requirements 7–8 in the Requirement 3.5 order.
 *
 * <p>There is no CREATE or DELETE handler; the ADMIN / MANAGER CREATE and DELETE grants on
 * {@code WORK_SCHEDULE} are permission-matrix completeness only (design D13).
 *
 * <p>Requirements: 2.1, 2.2, 3.4
 */
@RestController
@RequestMapping("/api/project-schedules")
@RequiredArgsConstructor
@PermissionResource("WORK_SCHEDULE")
public class ProjectScheduleController {

    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";

    private final ProjectScheduleService projectScheduleService;

    /**
     * Reads the {@link ScheduleView} of the given project (Requirement 5): the ordered Schedule_Rows
     * with their volumes and bars, the Schedule_Anchor, crew size, finish day / date, and warnings.
     * A project with no Project_Schedule row reads with version 0 and every row unscheduled, creating
     * no row (Requirement 5.6). Money fields are present only for Money_Viewers (Requirement 5.3).
     * {@code WORK_SCHEDULE} READ.
     */
    @GetMapping
    @PermissionOperation(READ)
    public ResponseEntity<ScheduleView> getView(@RequestParam Long projectId) {
        return ResponseEntity.ok(projectScheduleService.getView(projectId));
    }

    /**
     * Saves the submitted bars (Requirement 7): sets or clears the Bar of each listed category,
     * leaves unlisted categories unchanged, purges Orphan_Bars, and increments the Schedule_Version
     * on a real change (a no-op save keeps the version and writes no audit row). The request carries
     * the Schedule_Version the client last read; a stale version yields 409 {@code
     * error.schedule.conflict}. Returns the refreshed {@link ScheduleView}. {@code WORK_SCHEDULE}
     * UPDATE.
     */
    @PutMapping("/bars")
    @PermissionOperation(UPDATE)
    public ResponseEntity<ScheduleView> saveBars(
            @RequestParam Long projectId, @Valid @RequestBody SaveBarsRequest request) {
        return ResponseEntity.ok(projectScheduleService.saveBars(projectId, request));
    }

    /**
     * Auto-creates the schedule (Requirement 8): replaces all bars with one consecutive bar per
     * Schedule_Row, each duration computed from the category value, the configured Daily_Output_Rate,
     * and the current Crew_Size, laid out from day 1 with every calendar day counting as a working
     * day. Requires at least one ACTIVE WORKER on the team and at least one Schedule_Row. The request
     * carries only the Schedule_Version (the rate always comes from configuration, Requirement 9.2).
     * Returns the refreshed {@link ScheduleView}. {@code WORK_SCHEDULE} UPDATE.
     */
    @PostMapping("/auto-create")
    @PermissionOperation(UPDATE)
    public ResponseEntity<ScheduleView> autoCreate(
            @RequestParam Long projectId, @Valid @RequestBody AutoCreateRequest request) {
        return ResponseEntity.ok(projectScheduleService.autoCreate(projectId, request));
    }

    /**
     * Reads the {@code schedule} readiness gate of the given project (Requirement 12): the current
     * row count, the number of scheduled rows, and the derived {@code DONE} / {@code PARTIAL} /
     * {@code BLOCKED} state. {@code WORK_SCHEDULE} READ.
     */
    @GetMapping("/readiness")
    @PermissionOperation(READ)
    public ResponseEntity<ScheduleReadiness> readiness(@RequestParam Long projectId) {
        return ResponseEntity.ok(projectScheduleService.readiness(projectId));
    }
}
