package com.foremen.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.WorkReportBatchRequest;
import com.foremen.controller.model.WorkReportBatchResponse;
import com.foremen.controller.model.WorkReportCreateRequest;
import com.foremen.controller.model.WorkReportCreateResponse;
import com.foremen.controller.model.WorkReportDtoExtendedModel;
import com.foremen.controller.model.WorkReportDtoModel;
import com.foremen.controller.model.WorkReportProgressDto;
import com.foremen.controller.model.WorkReportReviewReasonRequest;
import com.foremen.controller.model.WorkReportUpdateRequest;
import com.foremen.controller.model.WorkReportUpdateResponse;
import com.foremen.controller.model.mapper.WorkReportControllerMapper;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.WorkReportService;
import com.foremen.service.model.WorkReportServiceExtendedModel;
import com.foremen.service.model.WorkReportServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * FOR-06-01 (Requirements 10.1, 10.3, 10.6; design §4.2) — REST controller for the project-scoped
 * {@code WorkReport} resource, exposed under {@code /api/work-reports}.
 *
 * <p>A thin {@link AdminController} implementation wiring the {@link WorkReportService}, the
 * {@link WorkReportControllerMapper}, and the shared {@link AuditServiceMapper}. The generic
 * create / list / read / update / delete / count / metadata / i18n operations are inherited as
 * {@code default} methods from {@link AdminController}; the project-scope gate (and the WORKER
 * own-records / MANAGER no-create / review-actor rules) is applied by the service.
 *
 * <p><b>ABAC (entity-creation-rules step 3).</b> The class-level
 * {@link PermissionResource @PermissionResource("WORK_REPORTS")} combines with the
 * {@code @PermissionOperation} declared on each inherited CRUD {@code default} method to produce the
 * {@code (resource, operation)} pair the {@code PermissionInterceptor} enforces and the
 * {@code PermissionAnnotationValidator} checks at startup — so the controller is fully annotated
 * (no half-annotation that would fail startup). The three bespoke review handlers below carry a
 * method-level {@link RequiresPermission @RequiresPermission}, which takes precedence over the
 * class {@code @PermissionResource} + method {@code @PermissionOperation} combination. The review
 * actions (approve / reject / request-changes) are state transitions on an existing report, so each
 * resolves to {@code WORK_REPORTS}/{@code UPDATE} (not a new operation); the extra actor rule — only
 * an ACTIVE FOREMAN approver / ADMIN may run them — is enforced in the service on top of the ABAC
 * UPDATE grant. The {@code WORK_REPORTS} resource and its grants are seeded by changeset 165.
 *
 * <p><b>Endpoints.</b> Base path {@code /api/work-reports}. Inherited CRUD: {@code POST} (create /
 * submit, or the FOREMAN fast-path approve), {@code GET ?query=} (list, project-scoped; a WORKER
 * sees only own), {@code GET /{id}} (read one), {@code PUT /{id}} (edit non-terminal / resubmit),
 * {@code DELETE /{id}} (delete). Bespoke review transitions: {@code POST /{id}/approve},
 * {@code POST /{id}/reject}, {@code POST /{id}/request-changes} (the last two carry a mandatory
 * reason). Each review handler returns the refreshed single-report projection
 * ({@link WorkReportDtoExtendedModel}) so the client sees the new status and decision trail.
 *
 * <p><b>Amendment A1 (matrix batch authoring).</b> Two further handlers back the matrix surface:
 * {@code GET /progress?projectId=&workerId=} ({@code WORK_REPORTS}/{@code READ}) returns the
 * {@link WorkReportProgressDto} matrix source, and {@code POST /batch}
 * ({@code WORK_REPORTS}/{@code CREATE}) bulk-creates a batch of single-report payloads in one
 * all-or-nothing transaction (a failing item yields a {@code 400} carrying its index / ids /
 * message, mapped in {@code ForemenControllerAdvice}). Both carry a method-level
 * {@link RequiresPermission @RequiresPermission}, so they are fully annotated for the
 * {@code PermissionAnnotationValidator} startup check.
 */
@RestController
@RequestMapping("/api/work-reports")
@RequiredArgsConstructor
@PermissionResource("WORK_REPORTS")
public class WorkReportController implements AdminController<
        WorkReportServiceModel,
        WorkReportServiceExtendedModel,
        WorkReportDtoModel,
        WorkReportDtoExtendedModel,
        WorkReportEntity,
        Long,
        WorkReportCreateRequest,
        WorkReportCreateResponse,
        WorkReportUpdateRequest,
        WorkReportUpdateResponse> {

    private static final String RESOURCE = "WORK_REPORTS";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";

    private final WorkReportService service;
    private final WorkReportControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<WorkReportServiceModel, WorkReportServiceExtendedModel,
            WorkReportDtoModel, WorkReportDtoExtendedModel,
            WorkReportCreateRequest, WorkReportCreateResponse,
            WorkReportUpdateRequest, WorkReportUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<WorkReportServiceModel, WorkReportServiceExtendedModel,
            WorkReportEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /**
     * Approves a {@code SUBMITTED} or {@code CHANGES_REQUESTED} report (Requirement 4.2): sets the
     * status to {@code APPROVED}, records {@code approvedBy}/{@code approvedAt}, and leaves the frozen
     * revenue valuation untouched. Only an ACTIVE FOREMAN approver of the report's project, or ADMIN,
     * may do so (Requirement 4.7, enforced in the service); the source status is guarded. Resolves to
     * {@code WORK_REPORTS}/{@code UPDATE}.
     *
     * @param id the target report id
     * @return the refreshed single-report projection with the new status / decision trail
     */
    @PostMapping("/{id}/approve")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<WorkReportDtoExtendedModel> approve(@PathVariable Long id) {
        WorkReportServiceExtendedModel updated = service.approve(id);
        return ResponseEntity.ok(controllerMapper.toExtendedDto(updated));
    }

    /**
     * Rejects a {@code SUBMITTED} report with a mandatory non-empty reason (Requirement 4.3):
     * {@code REJECTED} is terminal. FOREMAN/ADMIN only (Requirement 4.7); the source status is
     * guarded. Resolves to {@code WORK_REPORTS}/{@code UPDATE}.
     *
     * @param id      the target report id
     * @param request the body carrying the required rejection reason
     * @return the refreshed single-report projection with the new status / decision trail
     */
    @PostMapping("/{id}/reject")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<WorkReportDtoExtendedModel> reject(
            @PathVariable Long id,
            @Valid @RequestBody WorkReportReviewReasonRequest request) {
        WorkReportServiceExtendedModel updated = service.reject(id, request.reason());
        return ResponseEntity.ok(controllerMapper.toExtendedDto(updated));
    }

    /**
     * Returns a {@code SUBMITTED} report to its author for correction with a mandatory non-empty
     * reason (Requirement 4.4): the status becomes {@code CHANGES_REQUESTED}, from which the author
     * may edit + resubmit. FOREMAN/ADMIN only (Requirement 4.7); the source status is guarded.
     * Resolves to {@code WORK_REPORTS}/{@code UPDATE}.
     *
     * @param id      the target report id
     * @param request the body carrying the required reason
     * @return the refreshed single-report projection with the new status / decision trail
     */
    @PostMapping("/{id}/request-changes")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<WorkReportDtoExtendedModel> requestChanges(
            @PathVariable Long id,
            @Valid @RequestBody WorkReportReviewReasonRequest request) {
        WorkReportServiceExtendedModel updated = service.requestChanges(id, request.reason());
        return ResponseEntity.ok(controllerMapper.toExtendedDto(updated));
    }

    /**
     * FOR-06-01 Amendment A1 (Requirements 14.6, 16.1; design A1.1) — the matrix read projection
     * behind the batch authoring surface: for a {@code (project, worker)} it returns the worker's
     * not-yet-fully-reported Covering_Work rows grouped by category × room columns, each cell
     * carrying the planned and already-reported quantity (R14). Resolves to
     * {@code WORK_REPORTS}/{@code READ}; the service applies the project-scope gate and the WORKER
     * own-records guard (a WORKER requesting another {@code workerId} is rejected).
     *
     * @param projectId the project whose matrix source to read
     * @param workerId  the worker the matrix is for
     * @return the {@link WorkReportProgressDto} matrix source
     */
    @GetMapping("/progress")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<WorkReportProgressDto> progress(
            @RequestParam Long projectId,
            @RequestParam Long workerId) {
        return ResponseEntity.ok(service.progress(projectId, workerId));
    }

    /**
     * FOR-06-01 Amendment A1 (Requirements 16.1, 16.6; design A1.2) — bulk create / submit: persists
     * a non-empty batch of single-report payloads as one all-or-nothing transaction (R16.4). On full
     * success returns the persisted ids in submission order; a single failing item aborts (rolls
     * back) the whole batch and surfaces as a {@code 400} {@code WorkReportBatchItemError} carrying
     * the failing item's index / {@code workItemId} / optional {@code roomId} and localized message
     * (handled in {@code ForemenControllerAdvice}). Per-report notification events fire on commit
     * exactly as the single create path (R16.5). Resolves to {@code WORK_REPORTS}/{@code CREATE};
     * the service applies the project-scope gate and the own-records guard per item.
     *
     * @param request the batch payload ({@code reports[]})
     * @return the ids of the persisted reports
     */
    @PostMapping("/batch")
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<WorkReportBatchResponse> createBatch(
            @Valid @RequestBody WorkReportBatchRequest request) {
        List<WorkReportServiceExtendedModel> models = request.reports().stream()
                .map(controllerMapper::toServiceExtendedModel)
                .toList();
        List<WorkReportServiceExtendedModel> created = service.createBatch(models);
        List<Long> ids = created.stream()
                .map(WorkReportServiceExtendedModel::getId)
                .toList();
        return ResponseEntity.ok(new WorkReportBatchResponse(ids));
    }
}
