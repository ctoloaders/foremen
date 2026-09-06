package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.DeliveryCategoryServiceExtendedModel;
import com.foremen.service.model.DeliveryCategoryServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface DeliveryCategoryControllerMapper extends ControllerToServiceMapper<
        DeliveryCategoryServiceModel,
        DeliveryCategoryServiceExtendedModel,
        DeliveryCategoryDtoModel,
        DeliveryCategoryDtoExtendedModel,
        DeliveryCategoryCreateRequest,
        DeliveryCategoryCreateResponse,
        DeliveryCategoryUpdateRequest,
        DeliveryCategoryUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    DeliveryCategoryServiceExtendedModel toServiceExtendedModel(DeliveryCategoryCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    DeliveryCategoryServiceExtendedModel toUpdateServiceExtendedModel(DeliveryCategoryUpdateRequest source);
}
