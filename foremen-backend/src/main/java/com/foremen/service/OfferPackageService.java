package com.foremen.service;

import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.OfferPackageServiceExtendedModel;
import com.foremen.service.model.OfferPackageServiceModel;
import com.foremen.service.model.mapper.OfferPackageServiceMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Getter
public class OfferPackageService implements AdminService<
        OfferPackageServiceModel, OfferPackageServiceExtendedModel, OfferPackageEntity, Long> {

    private final OfferPackageDao dao;
    private final OfferPackageServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<OfferPackageEntity> daoModelClass = OfferPackageEntity.class;

    /**
     * Deletes an offer package and, in the SAME transaction, removes every construction
     * material AND finishing material that this delete left with zero packages (FOR-04-17
     * Requirement 5.2-5.4; FOR-04-18 Requirement 3.2-3.4).
     *
     * <p>The {@code construction_material_packages} and {@code finishing_material_packages}
     * join tables each declare their {@code offer_package_id} FK {@code ON DELETE CASCADE},
     * so deleting the package removes its join rows at the DB level (Requirements 5.2 /
     * 3.2). That cascade can leave a material with no remaining packages; because
     * {@code packages} is mandatory (every material always has &ge; 1 package unless a
     * cascade just emptied it) such a package-less row must itself be deleted
     * (Requirements 5.3 / 3.3), while materials that still hold at least one package are
     * left untouched (Requirements 5.4 / 3.4).
     *
     * <p>To scope the cleanup to only the rows this delete affected, the ids of the
     * materials that referenced the package are captured BEFORE the delete
     * (the join rows disappear during the cascade). After the standard delete + flush has
     * driven the cascade at the DB level, any of those captured materials that now hold
     * zero packages are deleted. The zero-package check reads the actual DB state via a
     * native query, so it reflects the post-cascade join table rather than a stale
     * persistence-context view.
     */
    @Override
    @Transactional
    public void deleteById(Long id) {
        // Capture the construction materials that reference this package BEFORE deletion;
        // the cascade removes the join rows, so the association is only readable up front.
        Query affectedQuery = entityManager.createNativeQuery(
                "SELECT construction_material_id FROM construction_material_packages "
                        + "WHERE offer_package_id = :packageId");
        affectedQuery.setParameter("packageId", id);
        @SuppressWarnings("unchecked")
        List<Number> affectedRows = affectedQuery.getResultList();
        List<Long> affectedMaterialIds = affectedRows.stream()
                .map(Number::longValue)
                .toList();

        // Capture the finishing materials that reference this package BEFORE deletion for
        // the same reason (FOR-04-18 Requirement 3.2-3.4).
        Query affectedFinishingQuery = entityManager.createNativeQuery(
                "SELECT finishing_material_id FROM finishing_material_packages "
                        + "WHERE offer_package_id = :packageId");
        affectedFinishingQuery.setParameter("packageId", id);
        @SuppressWarnings("unchecked")
        List<Number> affectedFinishingRows = affectedFinishingQuery.getResultList();
        List<Long> affectedFinishingMaterialIds = affectedFinishingRows.stream()
                .map(Number::longValue)
                .toList();

        // Standard delete: audit, remove the package, and flush so the DB-level
        // ON DELETE CASCADE drops the join rows (Requirements 5.2 / 3.2).
        AdminService.super.deleteById(id);

        // Delete every affected construction material that now holds zero packages
        // (Requirement 5.3); materials still holding >= 1 package are untouched
        // (Requirement 5.4). Scoped to the captured ids so only rows that had referenced
        // the deleted package are considered.
        if (!affectedMaterialIds.isEmpty()) {
            Query cleanupQuery = entityManager.createNativeQuery(
                    "DELETE FROM construction_materials "
                            + "WHERE id IN (:affectedIds) "
                            + "AND id NOT IN (SELECT construction_material_id FROM construction_material_packages)");
            cleanupQuery.setParameter("affectedIds", affectedMaterialIds);
            cleanupQuery.executeUpdate();
            entityManager.flush();
        }

        // Delete every affected finishing material that now holds zero packages
        // (FOR-04-18 Requirement 3.3); materials still holding >= 1 package are untouched
        // (Requirement 3.4). Scoped to the captured ids so only rows that had referenced
        // the deleted package are considered.
        if (!affectedFinishingMaterialIds.isEmpty()) {
            Query finishingCleanupQuery = entityManager.createNativeQuery(
                    "DELETE FROM finishing_materials "
                            + "WHERE id IN (:affectedIds) "
                            + "AND id NOT IN (SELECT finishing_material_id FROM finishing_material_packages)");
            finishingCleanupQuery.setParameter("affectedIds", affectedFinishingMaterialIds);
            finishingCleanupQuery.executeUpdate();
            entityManager.flush();
        }
    }
}
