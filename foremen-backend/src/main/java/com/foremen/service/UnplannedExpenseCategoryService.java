package com.foremen.service;

import com.foremen.dao.UnplannedExpenseCategoryDao;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.UnplannedExpenseCategoryServiceExtendedModel;
import com.foremen.service.model.UnplannedExpenseCategoryServiceModel;
import com.foremen.service.model.mapper.UnplannedExpenseCategoryServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class UnplannedExpenseCategoryService implements AdminService<
        UnplannedExpenseCategoryServiceModel, UnplannedExpenseCategoryServiceExtendedModel, UnplannedExpenseCategoryEntity, Long> {

    private final UnplannedExpenseCategoryDao dao;
    private final UnplannedExpenseCategoryServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<UnplannedExpenseCategoryEntity> daoModelClass = UnplannedExpenseCategoryEntity.class;
}
