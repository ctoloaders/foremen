package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.MaterialTypeServiceExtendedModel;
import com.foremen.service.model.MaterialTypeServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface MaterialTypeServiceMapper
        extends ServiceToDaoMapper<MaterialTypeEntity, MaterialTypeServiceModel, MaterialTypeServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    MaterialTypeEntity toCreateDaoModel(MaterialTypeServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(MaterialTypeServiceExtendedModel source, @MappingTarget MaterialTypeEntity target);
}
