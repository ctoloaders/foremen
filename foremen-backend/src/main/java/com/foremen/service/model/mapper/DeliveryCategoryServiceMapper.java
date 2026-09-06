package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.DeliveryCategoryEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.DeliveryCategoryServiceExtendedModel;
import com.foremen.service.model.DeliveryCategoryServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface DeliveryCategoryServiceMapper
        extends ServiceToDaoMapper<DeliveryCategoryEntity, DeliveryCategoryServiceModel, DeliveryCategoryServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    DeliveryCategoryEntity toCreateDaoModel(DeliveryCategoryServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(DeliveryCategoryServiceExtendedModel source, @MappingTarget DeliveryCategoryEntity target);
}
