package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.UnplannedExpenseCategoryControllerMapper;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.UnplannedExpenseCategoryService;
import com.foremen.service.model.UnplannedExpenseCategoryServiceExtendedModel;
import com.foremen.service.model.UnplannedExpenseCategoryServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/unplanned-expense-categories")
@RequiredArgsConstructor
@PermissionResource("UNPLANNED_EXPENSE_CATEGORIES")
public class UnplannedExpenseCategoryController implements AdminController<
        UnplannedExpenseCategoryServiceModel,
        UnplannedExpenseCategoryServiceExtendedModel,
        UnplannedExpenseCategoryDtoModel,
        UnplannedExpenseCategoryDtoExtendedModel,
        UnplannedExpenseCategoryEntity,
        Long,
        UnplannedExpenseCategoryCreateRequest,
        UnplannedExpenseCategoryCreateResponse,
        UnplannedExpenseCategoryUpdateRequest,
        UnplannedExpenseCategoryUpdateResponse> {

    private final UnplannedExpenseCategoryService service;
    private final UnplannedExpenseCategoryControllerMapper controllerMapper;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<UnplannedExpenseCategoryServiceModel, UnplannedExpenseCategoryServiceExtendedModel,
            UnplannedExpenseCategoryDtoModel, UnplannedExpenseCategoryDtoExtendedModel,
            UnplannedExpenseCategoryCreateRequest, UnplannedExpenseCategoryCreateResponse,
            UnplannedExpenseCategoryUpdateRequest, UnplannedExpenseCategoryUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<UnplannedExpenseCategoryServiceModel, UnplannedExpenseCategoryServiceExtendedModel,
            UnplannedExpenseCategoryEntity, Long> getService() {
        return service;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }
}
