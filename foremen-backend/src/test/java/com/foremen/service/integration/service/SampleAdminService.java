package com.foremen.service.integration.service;

import com.foremen.dao.AdminDao;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.AdminService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.integration.dao.SampleEntityDao;
import com.foremen.service.integration.entity.SampleEntity;
import com.foremen.service.integration.entity.SampleServiceExtendedModel;
import com.foremen.service.integration.entity.SampleServiceModel;
import com.foremen.service.integration.mapper.SampleEntityMapper;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminService implementation for SampleEntity.
 * Used by AdminServiceIntegrationTest for write-operation integration testing.
 */
@Service
@Transactional
public class SampleAdminService
        implements AdminService<SampleServiceModel, SampleServiceExtendedModel, SampleEntity, Long> {

    private final SampleEntityDao sampleEntityDao;
    private final SampleEntityMapper sampleEntityMapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;

    public SampleAdminService(SampleEntityDao sampleEntityDao,
                               SampleEntityMapper sampleEntityMapper,
                               AuditLogDao auditLogDao,
                               EntityManager entityManager) {
        this.sampleEntityDao = sampleEntityDao;
        this.sampleEntityMapper = sampleEntityMapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
    }

    @Override
    public AdminDao<SampleEntity, Long> getDao() {
        return sampleEntityDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<SampleEntity, SampleServiceModel, SampleServiceExtendedModel> getMapper() {
        return sampleEntityMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<SampleEntity> getDaoModelClass() {
        return SampleEntity.class;
    }
}
