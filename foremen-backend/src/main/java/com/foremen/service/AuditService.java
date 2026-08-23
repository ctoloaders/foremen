package com.foremen.service;

import com.foremen.dao.AuditReadOnlyDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.model.AuditServiceExtendedModel;
import com.foremen.service.model.AuditServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class AuditService implements ReadOnlyAdminService<
        AuditServiceModel, AuditServiceExtendedModel, AuditLogEntity, Long> {

    private final AuditReadOnlyDao readDao;
    private final AuditServiceMapper mapper;
    private final EntityManager entityManager;
    private final Class<AuditLogEntity> daoModelClass = AuditLogEntity.class;
}
