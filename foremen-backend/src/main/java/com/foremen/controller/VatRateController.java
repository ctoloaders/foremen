package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.VatRateControllerMapper;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.VatRateService;
import com.foremen.service.model.VatRateServiceExtendedModel;
import com.foremen.service.model.VatRateServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vat-rates")
@RequiredArgsConstructor
@PermissionResource("VAT_RATES")
public class VatRateController implements AdminController<
        VatRateServiceModel,
        VatRateServiceExtendedModel,
        VatRateDtoModel,
        VatRateDtoExtendedModel,
        VatRateEntity,
        Long,
        VatRateCreateRequest,
        VatRateCreateResponse,
        VatRateUpdateRequest,
        VatRateUpdateResponse> {

    private final VatRateService service;
    private final VatRateControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<VatRateServiceModel, VatRateServiceExtendedModel,
            VatRateDtoModel, VatRateDtoExtendedModel,
            VatRateCreateRequest, VatRateCreateResponse,
            VatRateUpdateRequest, VatRateUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<VatRateServiceModel, VatRateServiceExtendedModel,
            VatRateEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
