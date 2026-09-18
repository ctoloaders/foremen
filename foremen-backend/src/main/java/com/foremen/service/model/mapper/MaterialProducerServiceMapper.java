package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.MaterialProducerServiceExtendedModel;
import com.foremen.service.model.MaterialProducerServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface MaterialProducerServiceMapper
        extends ServiceToDaoMapper<MaterialProducerEntity, MaterialProducerServiceModel, MaterialProducerServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    MaterialProducerEntity toCreateDaoModel(MaterialProducerServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(MaterialProducerServiceExtendedModel source, @MappingTarget MaterialProducerEntity target);
}
