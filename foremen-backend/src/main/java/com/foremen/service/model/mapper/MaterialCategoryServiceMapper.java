package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.MaterialCategoryServiceExtendedModel;
import com.foremen.service.model.MaterialCategoryServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface MaterialCategoryServiceMapper
        extends ServiceToDaoMapper<MaterialCategoryEntity, MaterialCategoryServiceModel, MaterialCategoryServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    MaterialCategoryEntity toCreateDaoModel(MaterialCategoryServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(MaterialCategoryServiceExtendedModel source, @MappingTarget MaterialCategoryEntity target);
}
