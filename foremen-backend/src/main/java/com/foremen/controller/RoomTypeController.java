package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.RoomTypeControllerMapper;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.RoomTypeService;
import com.foremen.service.model.RoomTypeServiceExtendedModel;
import com.foremen.service.model.RoomTypeServiceModel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/room-types")
@RequiredArgsConstructor
@PermissionResource("ROOM_TYPES")
public class RoomTypeController implements AdminController<
        RoomTypeServiceModel,
        RoomTypeServiceExtendedModel,
        RoomTypeDtoModel,
        RoomTypeDtoExtendedModel,
        RoomTypeEntity,
        Long,
        RoomTypeCreateRequest,
        RoomTypeCreateResponse,
        RoomTypeUpdateRequest,
        RoomTypeUpdateResponse> {

    private final RoomTypeService service;
    private final RoomTypeControllerMapper controllerMapper;

    @Override
    public ControllerToServiceMapper<RoomTypeServiceModel, RoomTypeServiceExtendedModel,
            RoomTypeDtoModel, RoomTypeDtoExtendedModel,
            RoomTypeCreateRequest, RoomTypeCreateResponse,
            RoomTypeUpdateRequest, RoomTypeUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<RoomTypeServiceModel, RoomTypeServiceExtendedModel,
            RoomTypeEntity, Long> getService() {
        return service;
    }
}
