package com.foremen.service.integration.service;

import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ReadOnlyAdminService;
import com.foremen.service.integration.dao.SampleEntityDao;
import com.foremen.service.integration.entity.SampleEntity;
import com.foremen.service.integration.entity.SampleServiceExtendedModel;
import com.foremen.service.integration.entity.SampleServiceModel;
import com.foremen.service.integration.mapper.SampleEntityMapper;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class SampleReadOnlyService
        implements ReadOnlyAdminService<SampleServiceModel, SampleServiceExtendedModel, SampleEntity, Long> {

    private final SampleEntityDao sampleEntityDao;
    private final SampleEntityMapper sampleEntityMapper;
    private final EntityManager entityManager;

    public SampleReadOnlyService(SampleEntityDao sampleEntityDao,
                                  SampleEntityMapper sampleEntityMapper,
                                  EntityManager entityManager) {
        this.sampleEntityDao = sampleEntityDao;
        this.sampleEntityMapper = sampleEntityMapper;
        this.entityManager = entityManager;
    }

    @Override
    public ServiceToDaoMapper<SampleEntity, SampleServiceModel, SampleServiceExtendedModel> getMapper() {
        return sampleEntityMapper;
    }

    @Override
    public ReadOnlyAdminDao<SampleEntity, Long> getReadDao() {
        return sampleEntityDao;
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
