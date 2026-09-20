package com.foremen.service;

import com.foremen.dao.MaterialProducerDao;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.MaterialProducerServiceExtendedModel;
import com.foremen.service.model.MaterialProducerServiceModel;
import com.foremen.service.model.mapper.MaterialProducerServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;

/**
 * Service for the material producer vertical. A GLOBAL admin resource (does NOT implement
 * {@code ProjectScopedService}). Beyond the generic {@link AdminService} CRUD it owns the eager
 * orphan-cleanup triggers for the producer {@code image} (FOR-04-17, Requirements 8.4, 7.8, 7.9):
 * when a producer's image object key is replaced, detached (set to null) or the producer is
 * deleted, the now-unreferenced bucket object is reclaimed via
 * {@link ImageStorage#deleteIfOrphan(String)}. The cleanup runs AFTER the new state is persisted
 * and flushed so the shared reference lookup inside {@code deleteIfOrphan} sees the current DB
 * state (a key still referenced elsewhere is kept).
 */
@Service
@RequiredArgsConstructor
@Getter
public class MaterialProducerService implements AdminService<
        MaterialProducerServiceModel, MaterialProducerServiceExtendedModel, MaterialProducerEntity, Long> {

    private final MaterialProducerDao dao;
    private final MaterialProducerServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ImageStorage imageStorage;
    private final Class<MaterialProducerEntity> daoModelClass = MaterialProducerEntity.class;

    /**
     * Updates a producer and, when the {@code image} object key changed to a different value, deletes
     * the previously referenced object if no DB row still references it (image replace/detach —
     * Requirement 8.4, 7.8, 7.9). The previous key is captured before the update; the delegate update
     * persists and flushes the new state, so {@code deleteIfOrphan} re-checks against the current DB.
     */
    @Override
    @Transactional
    public MaterialProducerServiceExtendedModel update(Long id, MaterialProducerServiceExtendedModel model) {
        // Capture the previous image key WITHOUT collapsing a present-but-imageless entity into an
        // empty Optional: Optional.map(getImage) returns empty when image is null, which spuriously
        // triggered the 404 branch for a producer that exists but simply has no image. The delegated
        // update below throws NOT_FOUND when the entity is truly absent, so no existence check is
        // needed here — just read the (nullable) image key.
        MaterialProducerEntity existing = getReadDao().findById(id).orElse(null);
        String previousKey = existing == null ? null : existing.getImage();

        MaterialProducerServiceExtendedModel result = AdminService.super.update(id, model);

        if (previousKey != null && !Objects.equals(previousKey, model.image())) {
            imageStorage.deleteIfOrphan(previousKey);
        }
        return result;
    }

    /**
     * Detaches (nulls) the named properties and, when {@code image} is among them, reclaims the
     * previously referenced object if now unreferenced (Requirement 8.4, 7.9). The delegate nulls and
     * flushes first, so {@code deleteIfOrphan} re-checks against the current DB state.
     */
    @Override
    @Transactional
    public void setPropertiesToNull(Long id, Set<String> propertyNames) {
        String previousKey = propertyNames.contains("image")
                ? getReadDao().findById(id).map(MaterialProducerEntity::getImage).orElse(null)
                : null;

        AdminService.super.setPropertiesToNull(id, propertyNames);

        if (previousKey != null) {
            imageStorage.deleteIfOrphan(previousKey);
        }
    }

    /**
     * Deletes a producer and reclaims its image object, if any, once no DB row references it
     * (Requirement 8.4, 7.9). The delegate deletes and flushes first, so {@code deleteIfOrphan}
     * re-checks against the current DB.
     */
    @Override
    @Transactional
    public void deleteById(Long id) {
        String previousKey = getReadDao().findById(id)
                .map(MaterialProducerEntity::getImage)
                .orElse(null);

        AdminService.super.deleteById(id);

        if (previousKey != null) {
            imageStorage.deleteIfOrphan(previousKey);
        }
    }
}
