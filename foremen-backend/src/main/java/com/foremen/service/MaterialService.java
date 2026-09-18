package com.foremen.service;

import com.foremen.dao.MaterialDao;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.MaterialServiceExtendedModel;
import com.foremen.service.model.MaterialServiceModel;
import com.foremen.service.model.mapper.MaterialServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class MaterialService implements AdminService<
        MaterialServiceModel, MaterialServiceExtendedModel, MaterialEntity, Long> {

    private final MaterialDao dao;
    private final MaterialServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<MaterialEntity> daoModelClass = MaterialEntity.class;
}
