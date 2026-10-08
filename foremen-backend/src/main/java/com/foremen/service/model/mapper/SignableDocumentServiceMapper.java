package com.foremen.service.model.mapper;

import java.util.Collections;
import java.util.Set;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.SignableDocumentServiceExtendedModel;
import com.foremen.service.model.SignableDocumentServiceModel;

import jakarta.persistence.EntityManager;

/**
 * Service mapper for {@link SignableDocumentEntity} (FOR-05-08, Requirement 1), following the
 * FOR-05-07 {@code OfferServiceMapper} FK-resolution pattern: an <b>abstract class</b> holding an
 * injected {@link EntityManager} so the flat {@code projectId}/{@code documentTypeId} become managed
 * references via {@code getReference(...)} without a SELECT.
 *
 * <p>The document has no own i18n {@code name}, so {@link #getI18nSupportedProperties()} returns an
 * empty set. The read model resolves the bound type's {@code documentTypeCode} in the mapping.
 *
 * <p><b>Lifecycle-driven columns.</b> {@code status}/{@code contentHash}/{@code documentUri} and the
 * {@code createdByUser}/collection children are owned by {@code SignableDocumentService}'s dedicated
 * lifecycle methods — the generic CRUD mapping here merely round-trips the flat fields and never sets
 * the owner or children, which are stamped explicitly by the service on create/freeze.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class SignableDocumentServiceMapper
        implements ServiceToDaoMapper<SignableDocumentEntity,
                SignableDocumentServiceModel, SignableDocumentServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected ProjectEntity projectRef(Long id) {
        return id == null ? null : entityManager.getReference(ProjectEntity.class, id);
    }

    protected SignableDocumentTypeEntity documentTypeRef(Long id) {
        return id == null ? null : entityManager.getReference(SignableDocumentTypeEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", expression = "java(projectRef(source.getProjectId()))")
    @Mapping(target = "documentType", expression = "java(documentTypeRef(source.getDocumentTypeId()))")
    @Mapping(target = "createdByUser", ignore = true)
    @Mapping(target = "signatures", ignore = true)
    @Mapping(target = "formFields", ignore = true)
    @Mapping(target = "media", ignore = true)
    public abstract SignableDocumentEntity toCreateDaoModel(SignableDocumentServiceExtendedModel source);

    /**
     * Update-path mapping: {@code project}, {@code documentType}, and {@code createdByUser} are
     * deliberately left untouched (fixed at create time, R1.1); the signing lifecycle columns and
     * children are driven by the service, so the generic update only round-trips the flat scalars.
     */
    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "documentType", ignore = true)
    @Mapping(target = "createdByUser", ignore = true)
    @Mapping(target = "signatures", ignore = true)
    @Mapping(target = "formFields", ignore = true)
    @Mapping(target = "media", ignore = true)
    public abstract void updateFields(SignableDocumentServiceExtendedModel source,
                                      @MappingTarget SignableDocumentEntity target);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "documentTypeId", source = "documentType.id")
    @Mapping(target = "documentTypeCode", source = "documentType.code")
    @Mapping(target = "createdAt", source = "createdDate")
    public abstract SignableDocumentServiceModel toServiceModel(SignableDocumentEntity source);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "documentTypeId", source = "documentType.id")
    public abstract SignableDocumentServiceExtendedModel toServiceExtendedModel(SignableDocumentEntity source);
}
