package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.MeasurementUnitControllerMapper;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.MeasurementUnitService;
import com.foremen.service.model.MeasurementUnitServiceExtendedModel;
import com.foremen.service.model.MeasurementUnitServiceModel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/measurement-units")
@RequiredArgsConstructor
@PermissionResource("MEASUREMENT_UNITS")
public class MeasurementUnitController implements AdminController<
        MeasurementUnitServiceModel,
        MeasurementUnitServiceExtendedModel,
        MeasurementUnitDtoModel,
        MeasurementUnitDtoExtendedModel,
        MeasurementUnitEntity,
        Long,
        MeasurementUnitCreateRequest,
        MeasurementUnitCreateResponse,
        MeasurementUnitUpdateRequest,
        MeasurementUnitUpdateResponse> {

    private final MeasurementUnitService service;
    private final MeasurementUnitControllerMapper controllerMapper;

    @Override
    public ControllerToServiceMapper<MeasurementUnitServiceModel, MeasurementUnitServiceExtendedModel,
            MeasurementUnitDtoModel, MeasurementUnitDtoExtendedModel,
            MeasurementUnitCreateRequest, MeasurementUnitCreateResponse,
            MeasurementUnitUpdateRequest, MeasurementUnitUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<MeasurementUnitServiceModel, MeasurementUnitServiceExtendedModel,
            MeasurementUnitEntity, Long> getService() {
        return service;
    }
}
