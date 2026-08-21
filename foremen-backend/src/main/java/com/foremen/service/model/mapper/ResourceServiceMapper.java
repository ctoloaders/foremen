package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.ResourceServiceExtendedModel;
import com.foremen.service.model.ResourceServiceModel;
import org.mapstruct.Mapper;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface ResourceServiceMapper
        extends ServiceToDaoMapper<ResourceEntity, ResourceServiceModel, ResourceServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name", "description");
    }
}
