package com.foremen.service.integration.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.integration.entity.SampleEntity;
import com.foremen.service.integration.entity.SampleServiceExtendedModel;
import com.foremen.service.integration.entity.SampleServiceModel;
import org.mapstruct.Mapper;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface SampleEntityMapper
        extends ServiceToDaoMapper<SampleEntity, SampleServiceModel, SampleServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }
}
