package com.foremen.controller.model.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.OfferPackageCreateRequest;
import com.foremen.controller.model.OfferPackageCreateResponse;
import com.foremen.controller.model.OfferPackageDtoExtendedModel;
import com.foremen.controller.model.OfferPackageDtoModel;
import com.foremen.controller.model.OfferPackageUpdateRequest;
import com.foremen.controller.model.OfferPackageUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.OfferPackageServiceExtendedModel;
import com.foremen.service.model.OfferPackageServiceModel;

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
    @Mapping(target = "zlM2", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    OfferPackageServiceExtendedModel toServiceExtendedModel(OfferPackageCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "zlM2", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    OfferPackageServiceExtendedModel toUpdateServiceExtendedModel(OfferPackageUpdateRequest source);
}
