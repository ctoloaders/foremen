package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.MaterialTypeServiceExtendedModel;
import com.foremen.service.model.MaterialTypeServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface MaterialTypeControllerMapper extends ControllerToServiceMapper<
        MaterialTypeServiceModel,
        MaterialTypeServiceExtendedModel,
        MaterialTypeDtoModel,
        MaterialTypeDtoExtendedModel,
        MaterialTypeCreateRequest,
        MaterialTypeCreateResponse,
        MaterialTypeUpdateRequest,
        MaterialTypeUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    MaterialTypeServiceExtendedModel toServiceExtendedModel(MaterialTypeCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    MaterialTypeServiceExtendedModel toUpdateServiceExtendedModel(MaterialTypeUpdateRequest source);
}
