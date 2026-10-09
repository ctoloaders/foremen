package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.ProjectMemberServiceExtendedModel;
import com.foremen.service.model.ProjectMemberServiceModel;
import jakarta.persistence.EntityManager;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.Set;

/**
 * Service mapper for {@link ProjectMemberEntity} (FOR-05-09), following the FOR-04-14
 * {@code RoomServiceMapper} FK pattern: an <b>abstract class</b> holding an injected
 * {@link EntityManager} so it can turn the flat {@code userId} / {@code projectRoleId} /
 * {@code workerTypeId} into managed references via {@code getReference(...)} without triggering a
 * SELECT.
 *
 * <p>A membership has no own i18n {@code name}, so {@link #getI18nSupportedProperties()} returns an
 * empty set. The read models expose the flat {@code userId} / {@code projectRoleId} and the derived
 * {@code projectRoleCode}; the enriched, localized {@code Team_Member_View} is composed on top of
 * this base in a later task.
 *
 * <p>{@code projectId} is a plain {@code @Column} on the entity (the {@code projects} table arrives
 * in a later spec), so it is copied field-for-field. The ordered {@code tags} list and the
 * {@code assignmentStatus} enum are copied directly; the service owns tag normalization and the
 * status lifecycle before the mapper runs.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class ProjectMemberServiceMapper
        implements ServiceToDaoMapper<ProjectMemberEntity, ProjectMemberServiceModel, ProjectMemberServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected UserEntity userRef(Long id) {
        return id == null ? null : entityManager.getReference(UserEntity.class, id);
    }

    protected RoleEntity roleRef(Long id) {
        return id == null ? null : entityManager.getReference(RoleEntity.class, id);
    }

    protected WorkerTypeEntity workerTypeRef(Long id) {
        return id == null ? null : entityManager.getReference(WorkerTypeEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", expression = "java(userRef(source.getUserId()))")
    @Mapping(target = "projectRole", expression = "java(roleRef(source.getProjectRoleId()))")
    @Mapping(target = "workerType", expression = "java(workerTypeRef(source.getWorkerTypeId()))")
    public abstract ProjectMemberEntity toCreateDaoModel(ProjectMemberServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", expression = "java(userRef(source.getUserId()))")
    @Mapping(target = "projectRole", expression = "java(roleRef(source.getProjectRoleId()))")
    @Mapping(target = "workerType", expression = "java(workerTypeRef(source.getWorkerTypeId()))")
    public abstract void updateFields(ProjectMemberServiceExtendedModel source, @MappingTarget ProjectMemberEntity target);

    @Override
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "projectRoleId", source = "projectRole.id")
    @Mapping(target = "projectRoleCode", source = "projectRole.code")
    @Mapping(target = "workerTypeId", source = "workerType.id")
    public abstract ProjectMemberServiceModel toServiceModel(ProjectMemberEntity source);

    @Override
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "projectRoleId", source = "projectRole.id")
    @Mapping(target = "workerTypeId", source = "workerType.id")
    public abstract ProjectMemberServiceExtendedModel toServiceExtendedModel(ProjectMemberEntity source);
}
