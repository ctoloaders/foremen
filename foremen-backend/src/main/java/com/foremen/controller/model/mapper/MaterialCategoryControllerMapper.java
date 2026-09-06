package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.MaterialCategoryServiceExtendedModel;
import com.foremen.service.model.MaterialCategoryServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface MaterialCategoryControllerMapper extends ControllerToServiceMapper<
        MaterialCategoryServiceModel,
        MaterialCategoryServiceExtendedModel,
        MaterialCategoryDtoModel,
        MaterialCategoryDtoExtendedModel,
        MaterialCategoryCreateRequest,
        MaterialCategoryCreateResponse,
        MaterialCategoryUpdateRequest,
        MaterialCategoryUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    MaterialCategoryServiceExtendedModel toServiceExtendedModel(MaterialCategoryCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    MaterialCategoryServiceExtendedModel toUpdateServiceExtendedModel(MaterialCategoryUpdateRequest source);
}
