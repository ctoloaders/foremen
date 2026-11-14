package com.foremen.service.model.mapper;

import java.util.Set;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.ProjectMaterialServiceExtendedModel;
import com.foremen.service.model.ProjectMaterialServiceModel;

import jakarta.persistence.EntityManager;

/**
 * Service mapper for Project_Materials (FOR-11-00 R3). The row is a {@link FinishingMaterialEntity}
 * with {@code project} set, so all field/reference/package/label/photo mapping is delegated to
 * {@link FinishingMaterialServiceMapper}; this class only adds {@code project} (set on create, never
 * touched on update) and {@code seller}.
 */
@Component
public class ProjectMaterialServiceMapper implements ServiceToDaoMapper<FinishingMaterialEntity,
        ProjectMaterialServiceModel, ProjectMaterialServiceExtendedModel> {

    private final FinishingMaterialServiceMapper delegate;
    private final EntityManager entityManager;

    public ProjectMaterialServiceMapper(FinishingMaterialServiceMapper delegate, EntityManager entityManager) {
        this.delegate = delegate;
        this.entityManager = entityManager;
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    @Override
    public ProjectMaterialServiceModel toServiceModel(FinishingMaterialEntity source) {
        if (source == null) {
            return null;
        }
        ProjectMaterialServiceModel target = new ProjectMaterialServiceModel();
        BeanUtils.copyProperties(delegate.toServiceModel(source), target);
        target.setProjectId(source.getProject() == null ? null : source.getProject().getId());
        return target;
    }

    @Override
    public ProjectMaterialServiceExtendedModel toServiceExtendedModel(FinishingMaterialEntity source) {
        if (source == null) {
            return null;
        }
        ProjectMaterialServiceExtendedModel target = new ProjectMaterialServiceExtendedModel();
        BeanUtils.copyProperties(delegate.toServiceExtendedModel(source), target);
        target.setProjectId(source.getProject() == null ? null : source.getProject().getId());
        target.setSellerId(source.getSeller() == null ? null : source.getSeller().getId());
        return target;
    }

    @Override
    public FinishingMaterialEntity toCreateDaoModel(ProjectMaterialServiceExtendedModel source) {
        FinishingMaterialEntity entity = delegate.toCreateDaoModel(source);
        entity.setProject(source.getProjectId() == null ? null
                : entityManager.getReference(ProjectEntity.class, source.getProjectId()));
        entity.setSeller(source.getSellerId() == null ? null
                : entityManager.getReference(MaterialSellerEntity.class, source.getSellerId()));
        return entity;
    }

    @Override
    public void updateFields(ProjectMaterialServiceExtendedModel source, FinishingMaterialEntity target) {
        delegate.updateFields(source, target);
        // project is immutable: never reassigned. A null sellerId leaves the seller unchanged
        // (same null-ignore semantics as the other fields).
        if (source.getSellerId() != null) {
            target.setSeller(entityManager.getReference(MaterialSellerEntity.class, source.getSellerId()));
        }
    }
}
