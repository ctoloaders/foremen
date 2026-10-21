package com.foremen.service.model.mapper;

import java.util.Set;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.ClientMessageEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.ClientMessageServiceExtendedModel;
import com.foremen.service.model.ClientMessageServiceModel;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-12 (design §ClientMessageService) — service mapper for {@link ClientMessageEntity},
 * following the {@code WorkerPayoutServiceMapper} FK-resolution pattern: an <b>abstract class</b>
 * holding an injected {@link EntityManager} so a flat {@code projectId}/{@code authorId} becomes a
 * managed reference via {@code getReference(...)} without a SELECT.
 *
 * <p>This mapper completes the generic {@code AdminService} CRUD contract
 * {@code ClientMessageService} implements; the message lifecycle itself is owned by the service's
 * dedicated {@code post} / {@code listThread} / {@code markRead} endpoints (which build the entity
 * directly, derive {@code authorSide}, detect the locale, and persist the translations/media), not
 * by this field-by-field mapping. Accordingly the <b>write path</b> sets only the
 * {@code project}/{@code author} references and the author-supplied scalars and {@code ignore}s
 * every server-derived field ({@code authorSide}, {@code readAt}, {@code readBy}, the child
 * collections) plus the {@code BaseEntity} audit columns. The <b>read path</b> flattens the FK ids.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class ClientMessageServiceMapper
        implements ServiceToDaoMapper<ClientMessageEntity, ClientMessageServiceModel,
        ClientMessageServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected ProjectEntity projectRef(Long id) {
        return id == null ? null : entityManager.getReference(ProjectEntity.class, id);
    }

    protected UserEntity userRef(Long id) {
        return id == null ? null : entityManager.getReference(UserEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    // --- Write path ------------------------------------------------------------------------------

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", expression = "java(projectRef(source.getProjectId()))")
    @Mapping(target = "author", expression = "java(userRef(source.getAuthorId()))")
    @Mapping(target = "authorSide", ignore = true)
    @Mapping(target = "readAt", ignore = true)
    @Mapping(target = "readBy", ignore = true)
    @Mapping(target = "translations", ignore = true)
    @Mapping(target = "media", ignore = true)
    public abstract ClientMessageEntity toCreateDaoModel(ClientMessageServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "author", ignore = true)
    @Mapping(target = "authorSide", ignore = true)
    @Mapping(target = "readAt", ignore = true)
    @Mapping(target = "readBy", ignore = true)
    @Mapping(target = "translations", ignore = true)
    @Mapping(target = "media", ignore = true)
    public abstract void updateFields(ClientMessageServiceExtendedModel source,
                                      @MappingTarget ClientMessageEntity target);

    // --- Read path -------------------------------------------------------------------------------

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "authorId", source = "author.id")
    @Mapping(target = "readById", source = "readBy.id")
    public abstract ClientMessageServiceModel toServiceModel(ClientMessageEntity source);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "authorId", source = "author.id")
    @Mapping(target = "readById", source = "readBy.id")
    public abstract ClientMessageServiceExtendedModel toServiceExtendedModel(ClientMessageEntity source);
}
