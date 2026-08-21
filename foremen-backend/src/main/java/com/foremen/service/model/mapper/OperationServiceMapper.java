package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.OperationEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.OperationServiceExtendedModel;
import com.foremen.service.model.OperationServiceModel;
import org.mapstruct.Mapper;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface OperationServiceMapper
        extends ServiceToDaoMapper<OperationEntity, OperationServiceModel, OperationServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }
}
