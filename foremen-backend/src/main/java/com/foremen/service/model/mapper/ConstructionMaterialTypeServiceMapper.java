package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.ConstructionMaterialTypeServiceExtendedModel;
import com.foremen.service.model.ConstructionMaterialTypeServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface ConstructionMaterialTypeServiceMapper
        extends ServiceToDaoMapper<ConstructionMaterialTypeEntity, ConstructionMaterialTypeServiceModel,
        ConstructionMaterialTypeServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    ConstructionMaterialTypeEntity toCreateDaoModel(ConstructionMaterialTypeServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(ConstructionMaterialTypeServiceExtendedModel source,
                      @MappingTarget ConstructionMaterialTypeEntity target);
}
