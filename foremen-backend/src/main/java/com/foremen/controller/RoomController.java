package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.RoomControllerMapper;
import com.foremen.dao.model.RoomEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.RoomService;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.service.model.RoomServiceModel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
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

    private final RoomService service;
    private final RoomControllerMapper controllerMapper;

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
}
