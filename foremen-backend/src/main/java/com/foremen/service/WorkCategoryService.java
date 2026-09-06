package com.foremen.service;

import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkCategoryServiceExtendedModel;
import com.foremen.service.model.WorkCategoryServiceModel;
import com.foremen.service.model.mapper.WorkCategoryServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class WorkCategoryService implements AdminService<
        WorkCategoryServiceModel, WorkCategoryServiceExtendedModel, WorkCategoryEntity, Long> {

    private final WorkCategoryDao dao;
    private final WorkCategoryServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<WorkCategoryEntity> daoModelClass = WorkCategoryEntity.class;
}
