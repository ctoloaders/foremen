package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.dao.model.OperationEntity;
import com.foremen.service.OperationService;
import com.foremen.service.ReadOnlyAdminService;
import com.foremen.service.model.OperationServiceExtendedModel;
import com.foremen.service.model.OperationServiceModel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
@PermissionResource("OPERATIONS")
public class OperationController implements AdminReadOnlyController<
        OperationServiceModel, OperationServiceExtendedModel, OperationEntity, Long> {

    private final OperationService operationService;

    @Override
    public ReadOnlyAdminService<OperationServiceModel, OperationServiceExtendedModel, OperationEntity, Long> getService() {
        return operationService;
    }
}
