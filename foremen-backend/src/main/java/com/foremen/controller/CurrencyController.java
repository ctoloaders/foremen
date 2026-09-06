package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.CurrencyControllerMapper;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.CurrencyService;
import com.foremen.service.model.CurrencyServiceExtendedModel;
import com.foremen.service.model.CurrencyServiceModel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/currencies")
@RequiredArgsConstructor
@PermissionResource("CURRENCIES")
public class CurrencyController implements AdminController<
        CurrencyServiceModel,
        CurrencyServiceExtendedModel,
        CurrencyDtoModel,
        CurrencyDtoExtendedModel,
        CurrencyEntity,
        Long,
        CurrencyCreateRequest,
        CurrencyCreateResponse,
        CurrencyUpdateRequest,
        CurrencyUpdateResponse> {

    private final CurrencyService service;
    private final CurrencyControllerMapper controllerMapper;

    @Override
    public ControllerToServiceMapper<CurrencyServiceModel, CurrencyServiceExtendedModel,
            CurrencyDtoModel, CurrencyDtoExtendedModel,
            CurrencyCreateRequest, CurrencyCreateResponse,
            CurrencyUpdateRequest, CurrencyUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<CurrencyServiceModel, CurrencyServiceExtendedModel,
            CurrencyEntity, Long> getService() {
        return service;
    }
}
