package com.foremen.service.model.mapper;

import java.util.Collections;
import java.util.Set;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.ProjectScheduleServiceExtendedModel;
import com.foremen.service.model.ProjectScheduleServiceModel;

import jakarta.persistence.EntityManager;

/**
 * Service mapper for {@link ProjectScheduleEntity} (FOR-05-10, Requirement 6), following the
 * FOR-05-08 {@code SignableDocumentServiceMapper} FK-resolution pattern: an <b>abstract class</b>
 * holding an injected {@link EntityManager} so the flat {@code projectId} becomes a managed
 * {@code ProjectEntity} reference via {@code getReference(...)} without a SELECT.
 *
 * <p>The schedule has no own i18n {@code name}, so {@link #getI18nSupportedProperties()} returns an
 * empty set. The {@code version} (JPA {@code @Version}) and the owned {@code bars} collection are
 * driven by {@code ProjectScheduleService}'s dedicated save-bars / auto-create methods, so the
 * generic CRUD mapping here never writes {@code version} or {@code bars} — it only round-trips the
 * flat scalars. No field carries a man-days value or the internal {@code Daily_Output_Rate}
 * (Requirement 9.3).
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class ProjectScheduleServiceMapper
        implements ServiceToDaoMapper<ProjectScheduleEntity,
                ProjectScheduleServiceModel, ProjectScheduleServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected ProjectEntity projectRef(Long id) {
        return id == null ? null : entityManager.getReference(ProjectEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", expression = "java(projectRef(source.getProjectId()))")
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "bars", ignore = true)
    public abstract ProjectScheduleEntity toCreateDaoModel(ProjectScheduleServiceExtendedModel source);

    /**
     * Update-path mapping: {@code project}, {@code version}, and the owned {@code bars} collection are
     * deliberately left untouched (the project FK is fixed at create time, the version is the JPA
     * optimistic-lock counter, and the bars are driven by the service's save-bars / auto-create
     * methods), so the generic update only round-trips the flat scalars.
     */
    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "bars", ignore = true)
    public abstract void updateFields(ProjectScheduleServiceExtendedModel source,
                                      @MappingTarget ProjectScheduleEntity target);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    public abstract ProjectScheduleServiceModel toServiceModel(ProjectScheduleEntity source);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    public abstract ProjectScheduleServiceExtendedModel toServiceExtendedModel(ProjectScheduleEntity source);
}
