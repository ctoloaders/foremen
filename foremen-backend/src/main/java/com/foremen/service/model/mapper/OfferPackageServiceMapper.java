package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.OfferPackageServiceExtendedModel;
import com.foremen.service.model.OfferPackageServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface OfferPackageServiceMapper
        extends ServiceToDaoMapper<OfferPackageEntity, OfferPackageServiceModel, OfferPackageServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "zlM2", ignore = true)
    OfferPackageEntity toCreateDaoModel(OfferPackageServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "zlM2", ignore = true)
    void updateFields(OfferPackageServiceExtendedModel source, @MappingTarget OfferPackageEntity target);
}
