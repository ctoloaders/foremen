package com.foremen.service;

import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.ConstructionMaterialTypeServiceExtendedModel;
import com.foremen.service.model.ConstructionMaterialTypeServiceModel;
import com.foremen.service.model.mapper.ConstructionMaterialTypeServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class ConstructionMaterialTypeService implements AdminService<
        ConstructionMaterialTypeServiceModel, ConstructionMaterialTypeServiceExtendedModel,
        ConstructionMaterialTypeEntity, Long> {

    private final ConstructionMaterialTypeDao dao;
    private final ConstructionMaterialTypeServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<ConstructionMaterialTypeEntity> daoModelClass = ConstructionMaterialTypeEntity.class;
}
