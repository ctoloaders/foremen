package com.foremen.controller;

import java.util.List;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.RoomControllerMapper;
import com.foremen.dao.model.RoomEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.RoomService;
import com.foremen.service.estimate.recalc.EstimateImpact;
import com.foremen.service.estimate.recalc.RoomsSaveService;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.service.model.RoomServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin project-scoped CRUD controller for the room vertical (FOR-04-14), following the FOR-04
 * {@code WorkPriceController} pattern.
 *
 * <p>It implements {@link AdminController} and supplies only {@link #getMapper()} and
 * {@link #getService()}; the generic create/list/read/update/delete/count/metadata/i18n handlers are
 * inherited as {@code default} methods, each already carrying its {@code @PermissionOperation}. The
 * class-level {@link PermissionResource @PermissionResource("ROOMS")} combines with those operation
 * annotations to produce the {@code (resource, operation)} pairs enforced by the
 * {@code PermissionInterceptor} and validated at startup by {@code PermissionAnnotationValidator}
 * (Requirements 5.1, 6.1). The value {@code "ROOMS"} matches the seeded resource {@code code}.
 */
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
@PermissionResource("ROOMS")
public class RoomController implements AdminController<
        RoomServiceModel,
        RoomServiceExtendedModel,
        RoomDtoModel,
        RoomDtoExtendedModel,
        RoomEntity,
        Long,
        RoomCreateRequest,
        RoomCreateResponse,
        RoomUpdateRequest,
        RoomUpdateResponse> {

    private static final String ROOMS_RESOURCE = "ROOMS";

    private final RoomService service;
    private final RoomControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;
    private final RoomsSaveService roomsSaveService;
    private final ForemenPermissionEvaluator permissionEvaluator;

    /**
     * FOR-05-05 additional tasks 3–6 — the Rooms-tab atomic save with estimate auto-recalculation.
     * Applies the whole change set (creates, updates, deletes) in ONE transaction and re-derives the
     * project's estimate. Without {@code force}, a save that would change the estimate is rolled back
     * and answered with {@code 409 error.estimate.recalc.conflict} listing the changes; the client
     * re-sends the identical request with {@code force=true} once the user approves.
     *
     * <p>Guarded as {@code ROOMS} UPDATE; a non-empty create / delete group additionally requires
     * {@code ROOMS} CREATE / DELETE (checked programmatically, ADMIN bypass included).
     */
    @PostMapping("/project/{projectId}/save")
    @RequiresPermission(resource = ROOMS_RESOURCE, operation = "UPDATE")
    public ResponseEntity<RoomsSaveResponse> saveForProject(
            @PathVariable Long projectId,
            @RequestParam(name = "force", defaultValue = "false") boolean force,
            @Valid @RequestBody RoomsSaveRequest request) {
        List<RoomCreateRequest> createList = request.createList() == null ? List.of() : request.createList();
        List<BulkUpdateItem<Long, RoomUpdateRequest>> updateItems =
                request.updateItems() == null ? List.of() : request.updateItems();
        List<Long> deleteIds = request.deleteIds() == null ? List.of() : request.deleteIds();
        if (!createList.isEmpty()) {
            requireOperation("CREATE");
        }
        if (!deleteIds.isEmpty()) {
            requireOperation("DELETE");
        }

        RoomsSaveService.Result result = roomsSaveService.save(
                projectId,
                createList.stream().map(controllerMapper::toServiceExtendedModel).toList(),
                updateItems.stream()
                        .map(item -> new RoomsSaveService.RoomUpdate(
                                item.id(), controllerMapper.toUpdateServiceExtendedModel(item.data())))
                        .toList(),
                deleteIds,
                force);

        EstimateImpact impact = result.estimateImpact();
        return ResponseEntity.ok(new RoomsSaveResponse(
                result.created(),
                result.updated(),
                result.deleted(),
                !impact.isEmpty(),
                impact.changes().size(),
                impact.totalNetBefore(),
                impact.totalNetAfter(),
                result.skippedMedia(),
                result.skippedImportRefs()));
    }

    /**
     * FOR-05-02 bug-report Round 4 — sets the project's fixed room order (drag-and-drop on the Rooms
     * tab). Body {@code {"roomIds": [...]}} must list exactly the project's room ids in the new order;
     * otherwise 400 {@code error.room.order.invalid}. {@code ROOMS} UPDATE + project scope.
     */
    @org.springframework.web.bind.annotation.PutMapping("/project/{projectId}/order")
    @RequiresPermission(resource = ROOMS_RESOURCE, operation = "UPDATE")
    public ResponseEntity<Void> reorderForProject(
            @PathVariable Long projectId, @RequestBody RoomsReorderRequest request) {
        service.reorder(projectId, request == null ? null : request.roomIds());
        return ResponseEntity.noContent().build();
    }

    /** The reorder body: every room id of the project, in the new order. */
    public record RoomsReorderRequest(List<Long> roomIds) {
    }

    private void requireOperation(String operation) {
        String roleCode = currentRoleCode();
        if (roleCode == null || !permissionEvaluator.isAllowed(roleCode, ROOMS_RESOURCE, operation)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }
    }

    private static String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith("ROLE_")) {
                return authority.substring("ROLE_".length());
            }
        }
        return null;
    }

    @Override
    public ControllerToServiceMapper<RoomServiceModel, RoomServiceExtendedModel,
            RoomDtoModel, RoomDtoExtendedModel,
            RoomCreateRequest, RoomCreateResponse,
            RoomUpdateRequest, RoomUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<RoomServiceModel, RoomServiceExtendedModel,
            RoomEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
