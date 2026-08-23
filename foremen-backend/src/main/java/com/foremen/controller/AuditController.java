package com.foremen.controller;

import com.foremen.service.AuditService;
import com.foremen.service.ReadOnlyAdminService;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.model.AuditServiceExtendedModel;
import com.foremen.service.model.AuditServiceModel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController implements AdminReadOnlyController<
        AuditServiceModel, AuditServiceExtendedModel, AuditLogEntity, Long> {

    private final AuditService auditService;

    @Override
    public ReadOnlyAdminService<AuditServiceModel, AuditServiceExtendedModel, AuditLogEntity, Long> getService() {
        return auditService;
    }
}
