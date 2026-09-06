package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.WorkCategoryServiceExtendedModel;
import com.foremen.service.model.WorkCategoryServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface WorkCategoryControllerMapper extends ControllerToServiceMapper<
        WorkCategoryServiceModel,
        WorkCategoryServiceExtendedModel,
        WorkCategoryDtoModel,
        WorkCategoryDtoExtendedModel,
        WorkCategoryCreateRequest,
        WorkCategoryCreateResponse,
        WorkCategoryUpdateRequest,
        WorkCategoryUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    WorkCategoryServiceExtendedModel toServiceExtendedModel(WorkCategoryCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    WorkCategoryServiceExtendedModel toUpdateServiceExtendedModel(WorkCategoryUpdateRequest source);
}
