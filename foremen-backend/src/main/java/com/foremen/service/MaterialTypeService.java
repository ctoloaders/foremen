package com.foremen.service;

import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.MaterialTypeServiceExtendedModel;
import com.foremen.service.model.MaterialTypeServiceModel;
import com.foremen.service.model.mapper.MaterialTypeServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class MaterialTypeService implements AdminService<
        MaterialTypeServiceModel, MaterialTypeServiceExtendedModel, MaterialTypeEntity, Long> {

    private final MaterialTypeDao dao;
    private final MaterialTypeServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<MaterialTypeEntity> daoModelClass = MaterialTypeEntity.class;
}
