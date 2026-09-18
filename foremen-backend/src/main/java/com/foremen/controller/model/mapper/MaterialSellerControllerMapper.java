package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.MaterialSellerServiceExtendedModel;
import com.foremen.service.model.MaterialSellerServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface MaterialSellerControllerMapper extends ControllerToServiceMapper<
        MaterialSellerServiceModel,
        MaterialSellerServiceExtendedModel,
        MaterialSellerDtoModel,
        MaterialSellerDtoExtendedModel,
        MaterialSellerCreateRequest,
        MaterialSellerCreateResponse,
        MaterialSellerUpdateRequest,
        MaterialSellerUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    MaterialSellerServiceExtendedModel toServiceExtendedModel(MaterialSellerCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    MaterialSellerServiceExtendedModel toUpdateServiceExtendedModel(MaterialSellerUpdateRequest source);
}
