package com.foremen.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.StandardWorkSetItemCreateRequest;
import com.foremen.controller.model.StandardWorkSetItemCreateResponse;
import com.foremen.controller.model.StandardWorkSetItemDtoExtendedModel;
import com.foremen.controller.model.StandardWorkSetItemDtoModel;
import com.foremen.controller.model.StandardWorkSetItemUpdateRequest;
import com.foremen.controller.model.StandardWorkSetItemUpdateResponse;
import com.foremen.controller.model.mapper.StandardWorkSetItemControllerMapper;
import com.foremen.dao.model.StandardWorkSetItemEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.StandardWorkSetItemService;
import com.foremen.service.StandardWorkSetItemService.MatrixResult;
import com.foremen.service.StandardWorkSetItemService.Pair;
import com.foremen.service.model.StandardWorkSetItemServiceExtendedModel;
import com.foremen.service.model.StandardWorkSetItemServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;

import lombok.RequiredArgsConstructor;

/**
 * CRUD controller of the standard work set by room type (FOR-04-11 additional tasks 6-8), guarded by
 * the {@code STANDARD_WORK_SETS} resource (seeded by changeset 264). Only the inherited, fully
 * annotated generic operations are exposed.
 */
@RestController
@RequestMapping("/api/standard-work-sets")
@RequiredArgsConstructor
@PermissionResource("STANDARD_WORK_SETS")
public class StandardWorkSetItemController implements AdminController<
        StandardWorkSetItemServiceModel,
        StandardWorkSetItemServiceExtendedModel,
        StandardWorkSetItemDtoModel,
        StandardWorkSetItemDtoExtendedModel,
        StandardWorkSetItemEntity,
        Long,
        StandardWorkSetItemCreateRequest,
        StandardWorkSetItemCreateResponse,
        StandardWorkSetItemUpdateRequest,
        StandardWorkSetItemUpdateResponse> {

    private final StandardWorkSetItemService service;
    private final StandardWorkSetItemControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<StandardWorkSetItemServiceModel, StandardWorkSetItemServiceExtendedModel,
            StandardWorkSetItemDtoModel, StandardWorkSetItemDtoExtendedModel,
            StandardWorkSetItemCreateRequest, StandardWorkSetItemCreateResponse,
            StandardWorkSetItemUpdateRequest, StandardWorkSetItemUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<StandardWorkSetItemServiceModel, StandardWorkSetItemServiceExtendedModel,
            StandardWorkSetItemEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /** One cell of the matrix request / response. */
    public record MatrixCell(Long roomTypeId, Long workItemId) {}

    /** Body of {@code PUT /api/standard-work-sets/matrix}; either list may be {@code null} or empty. */
    public record MatrixRequest(List<MatrixCell> add, List<MatrixCell> remove) {}

    /** Response: rows really inserted / deleted, plus the full current set of pairs. */
    public record MatrixResponse(int added, int removed, List<MatrixCell> pairs) {}

    /**
     * Applies staged edits of the standard-set matrix in one transaction (FOR-04-11 Round 2).
     * Request: {@code {"add":[{"roomTypeId":1,"workItemId":2}],"remove":[...]}}. Idempotent (adding an
     * existing pair or removing a missing one is a no-op; removals run before additions). Responds
     * {@code 200 {"added":n,"removed":m,"pairs":[{"roomTypeId","workItemId"}...]}} where {@code pairs}
     * is the full current set; {@code 400 error.standard.work.set.matrix.unknown} for an unknown room
     * type or work (nothing is changed). Guarded by {@code (STANDARD_WORK_SETS, UPDATE)}.
     */
    @PutMapping("/matrix")
    @RequiresPermission(resource = "STANDARD_WORK_SETS", operation = "UPDATE")
    public ResponseEntity<MatrixResponse> applyMatrix(@RequestBody MatrixRequest request) {
        MatrixResult result = service.applyMatrix(toPairs(request.add()), toPairs(request.remove()));
        return ResponseEntity.ok(new MatrixResponse(result.added(), result.removed(),
                result.pairs().stream().map(p -> new MatrixCell(p.roomTypeId(), p.workItemId())).toList()));
    }

    private static List<Pair> toPairs(List<MatrixCell> cells) {
        if (cells == null) {
            return List.of();
        }
        return cells.stream()
                .map(c -> c == null ? new Pair(null, null) : new Pair(c.roomTypeId(), c.workItemId()))
                .toList();
    }
}
