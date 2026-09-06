package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.DeliveryStatusEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.DeliveryStatusServiceExtendedModel;
import com.foremen.service.model.DeliveryStatusServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface DeliveryStatusServiceMapper
        extends ServiceToDaoMapper<DeliveryStatusEntity, DeliveryStatusServiceModel, DeliveryStatusServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    DeliveryStatusEntity toCreateDaoModel(DeliveryStatusServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(DeliveryStatusServiceExtendedModel source, @MappingTarget DeliveryStatusEntity target);
}
