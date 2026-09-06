package com.foremen.service;

import com.foremen.dao.MaterialCategoryDao;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.MaterialCategoryServiceExtendedModel;
import com.foremen.service.model.MaterialCategoryServiceModel;
import com.foremen.service.model.mapper.MaterialCategoryServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class MaterialCategoryService implements AdminService<
        MaterialCategoryServiceModel, MaterialCategoryServiceExtendedModel, MaterialCategoryEntity, Long> {

    private final MaterialCategoryDao dao;
    private final MaterialCategoryServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<MaterialCategoryEntity> daoModelClass = MaterialCategoryEntity.class;
}
