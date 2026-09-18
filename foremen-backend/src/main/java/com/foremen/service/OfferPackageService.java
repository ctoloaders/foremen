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
     * material that this delete left with zero packages (FOR-04-17 Requirement 5.2-5.4).
     *
     * <p>The {@code construction_material_packages} join table declares its
     * {@code offer_package_id} FK {@code ON DELETE CASCADE}, so deleting the package
     * removes its join rows at the DB level (Requirement 5.2). That cascade can leave a
     * construction material with no remaining packages; because {@code packages} is
     * mandatory (every construction material always has &ge; 1 package unless a cascade
     * just emptied it) such a package-less row must itself be deleted (Requirement 5.3),
     * while materials that still hold at least one package are left untouched
     * (Requirement 5.4).
     *
     * <p>To scope the cleanup to only the rows this delete affected, the ids of the
     * construction materials that referenced the package are captured BEFORE the delete
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

        // Standard delete: audit, remove the package, and flush so the DB-level
        // ON DELETE CASCADE drops the join rows (Requirement 5.2).
        AdminService.super.deleteById(id);

        if (affectedMaterialIds.isEmpty()) {
            return;
        }

        // Delete every affected construction material that now holds zero packages
        // (Requirement 5.3); materials still holding >= 1 package are untouched
        // (Requirement 5.4). Scoped to the captured ids so only rows that had referenced
        // the deleted package are considered.
        Query cleanupQuery = entityManager.createNativeQuery(
                "DELETE FROM construction_materials "
                        + "WHERE id IN (:affectedIds) "
                        + "AND id NOT IN (SELECT construction_material_id FROM construction_material_packages)");
        cleanupQuery.setParameter("affectedIds", affectedMaterialIds);
        cleanupQuery.executeUpdate();
        entityManager.flush();
    }
}
