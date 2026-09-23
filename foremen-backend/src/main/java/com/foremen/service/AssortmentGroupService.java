package com.foremen.service;

import org.springframework.stereotype.Service;

import com.foremen.dao.AssortmentGroupDao;
import com.foremen.dao.model.AssortmentGroupEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.AssortmentGroupServiceExtendedModel;
import com.foremen.service.model.AssortmentGroupServiceModel;
import com.foremen.service.model.mapper.AssortmentGroupServiceMapper;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * CRUD service for {@link AssortmentGroupEntity} (FOR-05-04, Requirement 6.1; task 18.1).
 *
 * <p>A GLOBAL admin resource: it implements exactly {@link AdminService} and NOT
 * {@link ProjectScopedService} — a curated assortment group (e.g. "Sanitariat/łazienka",
 * "Podłoga") is a catalog resource with no project boundary, mirroring the simplicity of
 * {@code RoomTypeService}. No extra logic beyond the generic CRUD contract is needed here; the
 * package zł/m² computation (Requirement 6.3, 6.4, 6.5, 6.8) is exposed from
 * {@link AssortmentPositionService}, which is where the position/price data it depends on lives.
 */
@Service
@RequiredArgsConstructor
public class AssortmentGroupService implements AdminService<
        AssortmentGroupServiceModel, AssortmentGroupServiceExtendedModel,
        AssortmentGroupEntity, Long> {

    private final AssortmentGroupDao dao;
    private final AssortmentGroupServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;

    @Override
    public AssortmentGroupDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<AssortmentGroupEntity, AssortmentGroupServiceModel,
            AssortmentGroupServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<AssortmentGroupEntity> getDaoModelClass() {
        return AssortmentGroupEntity.class;
    }
}
