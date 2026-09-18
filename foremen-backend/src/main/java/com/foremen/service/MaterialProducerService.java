package com.foremen.service;

import com.foremen.dao.MaterialProducerDao;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.MaterialProducerServiceExtendedModel;
import com.foremen.service.model.MaterialProducerServiceModel;
import com.foremen.service.model.mapper.MaterialProducerServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class MaterialProducerService implements AdminService<
        MaterialProducerServiceModel, MaterialProducerServiceExtendedModel, MaterialProducerEntity, Long> {

    private final MaterialProducerDao dao;
    private final MaterialProducerServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<MaterialProducerEntity> daoModelClass = MaterialProducerEntity.class;
}
