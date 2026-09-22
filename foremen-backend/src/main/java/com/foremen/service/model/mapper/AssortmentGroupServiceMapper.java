package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.AssortmentGroupEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.AssortmentGroupServiceExtendedModel;
import com.foremen.service.model.AssortmentGroupServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

/**
 * Service mapper for {@link AssortmentGroupEntity} (FOR-05-04, Requirement 6.1).
 *
 * <p>An interface (following {@code RoomTypeServiceMapper}'s pattern for a simple i18n catalog
 * entity with no FK references to resolve): {@code name} is populated by the shared i18n
 * framework from {@code nameRU}/{@code namePL} (PL fallback) via
 * {@link #getI18nSupportedProperties()}.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface AssortmentGroupServiceMapper
        extends ServiceToDaoMapper<AssortmentGroupEntity, AssortmentGroupServiceModel,
        AssortmentGroupServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    AssortmentGroupEntity toCreateDaoModel(AssortmentGroupServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    void updateFields(AssortmentGroupServiceExtendedModel source, @MappingTarget AssortmentGroupEntity target);
}
