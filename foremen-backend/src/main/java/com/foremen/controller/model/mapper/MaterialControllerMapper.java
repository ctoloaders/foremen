package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.MaterialServiceExtendedModel;
import com.foremen.service.model.MaterialServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface MaterialControllerMapper extends ControllerToServiceMapper<
        MaterialServiceModel,
        MaterialServiceExtendedModel,
        MaterialDtoModel,
        MaterialDtoExtendedModel,
        MaterialCreateRequest,
        MaterialCreateResponse,
        MaterialUpdateRequest,
        MaterialUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    MaterialServiceExtendedModel toServiceExtendedModel(MaterialCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    MaterialServiceExtendedModel toUpdateServiceExtendedModel(MaterialUpdateRequest source);
}
