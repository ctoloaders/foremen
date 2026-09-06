package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.WorkPriceControllerMapper;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.WorkPriceService;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceModel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/work-prices")
@RequiredArgsConstructor
@PermissionResource("WORK_PRICES")
public class WorkPriceController implements AdminController<
        WorkPriceServiceModel,
        WorkPriceServiceExtendedModel,
        WorkPriceDtoModel,
        WorkPriceDtoExtendedModel,
        WorkPriceEntity,
        Long,
        WorkPriceCreateRequest,
        WorkPriceCreateResponse,
        WorkPriceUpdateRequest,
        WorkPriceUpdateResponse> {

    private final WorkPriceService service;
    private final WorkPriceControllerMapper controllerMapper;

    @Override
    public ControllerToServiceMapper<WorkPriceServiceModel, WorkPriceServiceExtendedModel,
            WorkPriceDtoModel, WorkPriceDtoExtendedModel,
            WorkPriceCreateRequest, WorkPriceCreateResponse,
            WorkPriceUpdateRequest, WorkPriceUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<WorkPriceServiceModel, WorkPriceServiceExtendedModel,
            WorkPriceEntity, Long> getService() {
        return service;
    }
}
