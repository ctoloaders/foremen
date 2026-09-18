package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.ConstructionMaterialTypeServiceExtendedModel;
import com.foremen.service.model.ConstructionMaterialTypeServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface ConstructionMaterialTypeControllerMapper extends ControllerToServiceMapper<
        ConstructionMaterialTypeServiceModel,
        ConstructionMaterialTypeServiceExtendedModel,
        ConstructionMaterialTypeDtoModel,
        ConstructionMaterialTypeDtoExtendedModel,
        ConstructionMaterialTypeCreateRequest,
        ConstructionMaterialTypeCreateResponse,
        ConstructionMaterialTypeUpdateRequest,
        ConstructionMaterialTypeUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    ConstructionMaterialTypeServiceExtendedModel toServiceExtendedModel(ConstructionMaterialTypeCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    ConstructionMaterialTypeServiceExtendedModel toUpdateServiceExtendedModel(
            ConstructionMaterialTypeUpdateRequest source);
}
