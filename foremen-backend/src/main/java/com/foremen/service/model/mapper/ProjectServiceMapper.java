package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.VatRateEntity;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.ProjectServiceExtendedModel;
import com.foremen.service.model.ProjectServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Collections;
import java.util.Set;

/**
 * Service mapper for {@link ProjectEntity} (FOR-04-13). {@code Project} is an operational entity
 * with plain descriptive fields (no {@code nameRU}/{@code namePL} i18n pair and no FK references in
 * its service model), so this is a simple interface-based MapStruct mapper: field names line up
 * one-to-one between the entity and the service models, and there are no localized properties
 * ({@link #getI18nSupportedProperties()} returns an empty set).
 *
 * <p>The read-only {@code members} collection on {@link ProjectEntity} is <strong>not</strong>
 * mapped here — it is projected onto the controller DTOs (with the derived {@code client}) in the
 * controller layer, and is never written back through this mapper. The write mappings therefore
 * ignore {@code members} so MapStruct does not attempt to populate the association.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class ProjectServiceMapper
        implements ServiceToDaoMapper<ProjectEntity, ProjectServiceModel, ProjectServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected VatRateEntity vatRateRef(Long id) {
        return id == null ? null : entityManager.getReference(VatRateEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "members", ignore = true)
    // FOR-05-04: the package flag / package are written only by the estimate apply/remove package path.
    @Mapping(target = "packageApplied", ignore = true)
    @Mapping(target = "offerPackage", ignore = true)
    @Mapping(target = "vatRate", expression = "java(vatRateRef(source.vatRateId()))")
    public abstract ProjectEntity toCreateDaoModel(ProjectServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "members", ignore = true)
    @Mapping(target = "packageApplied", ignore = true)
    @Mapping(target = "offerPackage", ignore = true)
    // A null vatRateId (older clients) keeps the stored rate instead of clearing it.
    @Mapping(target = "vatRate",
            expression = "java(source.vatRateId() == null ? target.getVatRate() : vatRateRef(source.vatRateId()))")
    public abstract void updateFields(ProjectServiceExtendedModel source, @MappingTarget ProjectEntity target);

    @Override
    @Mapping(target = "vatRateId", source = "vatRate.id")
    public abstract ProjectServiceModel toServiceModel(ProjectEntity source);

    @Override
    @Mapping(target = "vatRateId", source = "vatRate.id")
    public abstract ProjectServiceExtendedModel toServiceExtendedModel(ProjectEntity source);
}
