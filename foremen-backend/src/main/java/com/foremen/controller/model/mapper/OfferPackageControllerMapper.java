package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.OfferPackageServiceExtendedModel;
import com.foremen.service.model.OfferPackageServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface OfferPackageControllerMapper extends ControllerToServiceMapper<
        OfferPackageServiceModel,
        OfferPackageServiceExtendedModel,
        OfferPackageDtoModel,
        OfferPackageDtoExtendedModel,
        OfferPackageCreateRequest,
        OfferPackageCreateResponse,
        OfferPackageUpdateRequest,
        OfferPackageUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    OfferPackageServiceExtendedModel toServiceExtendedModel(OfferPackageCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    OfferPackageServiceExtendedModel toUpdateServiceExtendedModel(OfferPackageUpdateRequest source);
}
