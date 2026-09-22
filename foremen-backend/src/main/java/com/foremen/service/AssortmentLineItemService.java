package com.foremen.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AssortmentLineItemDao;
import com.foremen.dao.model.AssortmentLineItemEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.AssortmentLineItemServiceExtendedModel;
import com.foremen.service.model.AssortmentLineItemServiceModel;
import com.foremen.service.model.mapper.AssortmentLineItemServiceMapper;
import com.foremen.service.pricing.PackageZlM2Resolver;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * CRUD service for {@link AssortmentLineItemEntity} (FOR-05-04, Requirement 6.1, 6.2; task 18.1),
 * plus the package zł/m² exposure the downstream FOR-05-06 consumer reads (Requirement 6.3, 6.4,
 * 6.5, 6.8).
 *
 * <p>A GLOBAL admin resource: it implements exactly {@link AdminService} and NOT
 * {@link ProjectScopedService} — an assortment line item is a curated catalog row with no
 * project boundary, mirroring {@code AssortmentGroupService}/{@code WorkVolumeFormulaService}.
 *
 * <p><b>{@link #computePackageZlM2(String)}.</b> Adapts every currently-persisted
 * {@link AssortmentLineItemEntity} into {@link PackageZlM2Resolver.Line}, grouped by assortment
 * group id, and delegates to {@link PackageZlM2Resolver#packageZlM2}. The load + adaptation runs
 * fresh on every call — nothing is cached — so the result always reflects the current assortment
 * data (Requirement 6.5, "recomputed from current data"). The returned figure is the raw package
 * zł/m² price; it is NOT multiplied by any floor area — that step belongs to the downstream
 * FOR-05-06 consumer (Requirement 6.8), which is out of scope here.
 *
 * <p>{@code packageCode} (not {@code offerPackageId}) is the identifier accepted here, matching
 * {@link OfferPackageEntity#getCode()} as the stable, human-readable package identifier already
 * used at similar resolver call sites (e.g. {@code budget}/{@code norm}/{@code lux}) rather than
 * the numeric FK id, which is an implementation detail of the catalog row.
 */
@Service
@RequiredArgsConstructor
public class AssortmentLineItemService implements AdminService<
        AssortmentLineItemServiceModel, AssortmentLineItemServiceExtendedModel,
        AssortmentLineItemEntity, Long> {

    private final AssortmentLineItemDao dao;
    private final AssortmentLineItemServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final PackageZlM2Resolver packageZlM2Resolver;

    @Override
    public AssortmentLineItemDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<AssortmentLineItemEntity, AssortmentLineItemServiceModel,
            AssortmentLineItemServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<AssortmentLineItemEntity> getDaoModelClass() {
        return AssortmentLineItemEntity.class;
    }

    /**
     * Computes the package zł/m² price for {@code packageCode}, recomputed from the current
     * assortment data on every call (Requirement 6.5) and independent of any "typical product"
     * link (Requirement 6.6). The result is the raw zł/m² figure — the downstream FOR-05-06
     * consumer is responsible for multiplying it by a project's total floor area
     * (Requirement 6.8); this method does not apply that multiplication.
     *
     * @param packageCode the target {@code OfferPackage}'s {@code code} (e.g. {@code "budget"},
     *                    {@code "norm"}, {@code "lux"})
     * @return the package's zł/m² price for {@code packageCode}, rounded to 2 decimals
     */
    @Transactional(readOnly = true)
    public BigDecimal computePackageZlM2(String packageCode) {
        Map<Long, List<PackageZlM2Resolver.Line>> linesByGroup = new HashMap<>();

        for (AssortmentLineItemEntity entity : getDao().findAll()) {
            if (entity.getGroup() == null || entity.getOfferPackage() == null) {
                continue;
            }
            Long groupId = entity.getGroup().getId();
            PackageZlM2Resolver.Line line = new PackageZlM2Resolver.Line(
                    groupId,
                    entity.getOfferPackage().getCode(),
                    entity.getAvgPrice(),
                    entity.getQtyRef50());
            linesByGroup.computeIfAbsent(groupId, id -> new ArrayList<>()).add(line);
        }

        return packageZlM2Resolver.packageZlM2(linesByGroup, packageCode);
    }
}
