package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.AddLineRequest;
import com.foremen.controller.model.AmendmentCreateRequest;
import com.foremen.controller.model.AmendmentCreateResponse;
import com.foremen.controller.model.AmendmentDtoExtendedModel;
import com.foremen.controller.model.AmendmentDtoModel;
import com.foremen.controller.model.AmendmentUpdateRequest;
import com.foremen.controller.model.AmendmentUpdateResponse;
import com.foremen.controller.model.mapper.AmendmentControllerMapper;
import com.foremen.dao.model.EstimateAmendmentEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.execution.amendment.AmendmentService;
import com.foremen.service.model.AmendmentServiceExtendedModel;
import com.foremen.service.model.AmendmentServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * FOR-06-08 (Requirements 1.5, 3.7, 9.1, 9.4, 10.3; design §Backend components) — REST controller
 * for the project-scoped {@code EstimateAmendment} (aneks) resource, exposed under
 * {@code /api/amendments}.
 *
 * <p>A thin {@link AdminController} implementation wiring the {@link AmendmentService}, the
 * {@link AmendmentControllerMapper}, and the shared {@link AuditServiceMapper}. The generic
 * create / list / read / update / delete / count / metadata / i18n operations are inherited as
 * {@code default} methods from {@link AdminController}; the project-scope gate (and the
 * single-open-amendment / eligibility / {@code OPEN}-guard rules) is applied by the service.
 *
 * <p><b>ABAC (entity-creation-rules step 3).</b> The class-level
 * {@link PermissionResource @PermissionResource("AMENDMENTS")} combines with the
 * {@code @PermissionOperation} declared on each inherited CRUD {@code default} method to produce the
 * {@code (resource, operation)} pair the {@code PermissionInterceptor} enforces and the
 * {@code PermissionAnnotationValidator} checks at startup — so the controller is fully annotated
 * (no half-annotation that would fail startup). The bespoke handlers below carry a method-level
 * {@link RequiresPermission @RequiresPermission}, which takes precedence over the class
 * {@code @PermissionResource} + method {@code @PermissionOperation} combination. The
 * {@code AMENDMENTS} resource and its grants are seeded by changeset 179 (task 8.1).
 *
 * <p><b>Endpoints.</b> Base path {@code /api/amendments}. Inherited CRUD: {@code POST} (create the
 * project's {@code OPEN} amendment — the service enforces eligibility + the single-open invariant,
 * R2), {@code GET ?query=} (list, project-scoped, R1.5), {@code GET /{id}} (read one with its change
 * lines, R1.4), {@code PUT /{id}} (edit the note while {@code OPEN}). Bespoke handlers:
 * {@code GET /open?projectId=} ({@code AMENDMENTS}/{@code READ}) returns the project's single
 * {@code OPEN} amendment (or {@code 204} when none, R1.3); {@code POST /{id}/lines?projectId=}
 * ({@code AMENDMENTS}/{@code UPDATE}) adds / merges a change line (R3, R5); {@code DELETE
 * /{id}/lines/{lineId}} ({@code AMENDMENTS}/{@code DELETE}) removes a single line while {@code OPEN}
 * (R6.1); {@code DELETE /{id}} ({@code AMENDMENTS}/{@code DELETE}, overriding the generic delete to
 * enforce the {@code OPEN} guard) deletes the whole amendment (R6.2). Each handler is fully
 * annotated for the {@code PermissionAnnotationValidator} startup check.
 */
@RestController
@RequestMapping("/api/amendments")
@RequiredArgsConstructor
@PermissionResource("AMENDMENTS")
public class AmendmentController implements AdminController<
        AmendmentServiceModel,
        AmendmentServiceExtendedModel,
        AmendmentDtoModel,
        AmendmentDtoExtendedModel,
        EstimateAmendmentEntity,
        Long,
        AmendmentCreateRequest,
        AmendmentCreateResponse,
        AmendmentUpdateRequest,
        AmendmentUpdateResponse> {

    private static final String RESOURCE = "AMENDMENTS";
    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";

    private final AmendmentService service;
    private final AmendmentControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<AmendmentServiceModel, AmendmentServiceExtendedModel,
            AmendmentDtoModel, AmendmentDtoExtendedModel,
            AmendmentCreateRequest, AmendmentCreateResponse,
            AmendmentUpdateRequest, AmendmentUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<AmendmentServiceModel, AmendmentServiceExtendedModel,
            EstimateAmendmentEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /**
     * Returns the project's single {@code OPEN} amendment with its change lines (R1.3), or
     * {@code 204 No Content} when the project has none (so the UI can offer a create action). The
     * project-scope gate and the ownership check ride the service. Resolves to
     * {@code AMENDMENTS}/{@code READ}.
     *
     * @param projectId the owning project id
     * @return the open amendment projection, or {@code 204} when none exists
     */
    @GetMapping("/open")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<AmendmentDtoExtendedModel> getOpen(@RequestParam Long projectId) {
        AmendmentServiceExtendedModel open = service.getOpenOrNull(projectId);
        if (open == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(controllerMapper.toExtendedDto(open));
    }

    /**
     * Adds a change line to the project's {@code OPEN} amendment (R3), creating the open amendment
     * first if none exists (R3.5 / R2.2); a same-subject collision with no {@code mergeResolution}
     * is rejected {@code error.amendment.subjectConflict} (409) so the UI opens the merge chooser,
     * else the surviving line is merged in place (R5). The {@code OPEN} guard, per-type validation,
     * read-only baseline resolution, and the project-scope gate are all enforced in the service.
     * Resolves to {@code AMENDMENTS}/{@code UPDATE}.
     *
     * @param id        the open amendment id (REST resource reference)
     * @param projectId the owning project id (drives the service's open-amendment resolution)
     * @param request   the polymorphic add-change payload (optional {@code mergeResolution})
     * @return the amendment projection after the add / merge
     */
    @PostMapping("/{id}/lines")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<AmendmentDtoExtendedModel> addLine(
            @PathVariable Long id,
            @RequestParam Long projectId,
            @Valid @RequestBody AddLineRequest request) {
        AmendmentServiceExtendedModel updated = service.addLine(projectId, request);
        return ResponseEntity.ok(controllerMapper.toExtendedDto(updated));
    }

    /**
     * Deletes a single change line from an {@code OPEN} amendment, leaving the (possibly now empty)
     * amendment {@code OPEN} (R6.1 / R6.3). A non-{@code OPEN} amendment is refused
     * {@code error.amendment.notOpen} (R6.4); the project-scope gate rides the service. Resolves to
     * {@code AMENDMENTS}/{@code DELETE}.
     *
     * @param id     the owning amendment id
     * @param lineId the line to remove
     * @return the amendment projection after the deletion
     */
    @DeleteMapping("/{id}/lines/{lineId}")
    @RequiresPermission(resource = RESOURCE, operation = DELETE)
    public ResponseEntity<AmendmentDtoExtendedModel> deleteLine(
            @PathVariable Long id,
            @PathVariable Long lineId) {
        AmendmentServiceExtendedModel updated = service.deleteLine(id, lineId);
        return ResponseEntity.ok(controllerMapper.toExtendedDto(updated));
    }

    /**
     * Deletes a whole {@code OPEN} amendment so a fresh one can be created (R6.2); overrides the
     * generic {@link AdminController#deleteById} to route through {@link AmendmentService#deleteAmendment}
     * so the {@code OPEN} guard is enforced ({@code error.amendment.notOpen}, R6.4) and every
     * downstream baseline is left untouched (R6.5). The project-scope gate rides the service.
     * Resolves to {@code AMENDMENTS}/{@code DELETE}.
     *
     * @param id the amendment to delete
     * @return {@code 204 No Content}
     */
    @Override
    @DeleteMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = DELETE)
    public ResponseEntity<Void> deleteById(@PathVariable Long id) {
        service.deleteAmendment(id);
        return ResponseEntity.noContent().build();
    }
}
