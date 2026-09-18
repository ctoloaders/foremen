package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.MaterialSellerServiceExtendedModel;
import com.foremen.service.model.MaterialSellerServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface MaterialSellerServiceMapper
        extends ServiceToDaoMapper<MaterialSellerEntity, MaterialSellerServiceModel, MaterialSellerServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    MaterialSellerEntity toCreateDaoModel(MaterialSellerServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(MaterialSellerServiceExtendedModel source, @MappingTarget MaterialSellerEntity target);
}
