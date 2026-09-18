package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.MaterialServiceExtendedModel;
import com.foremen.service.model.MaterialServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface MaterialServiceMapper
        extends ServiceToDaoMapper<MaterialEntity, MaterialServiceModel, MaterialServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    MaterialEntity toCreateDaoModel(MaterialServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(MaterialServiceExtendedModel source, @MappingTarget MaterialEntity target);
}
