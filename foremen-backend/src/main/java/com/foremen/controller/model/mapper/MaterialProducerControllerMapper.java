package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.MaterialProducerServiceExtendedModel;
import com.foremen.service.model.MaterialProducerServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface MaterialProducerControllerMapper extends ControllerToServiceMapper<
        MaterialProducerServiceModel,
        MaterialProducerServiceExtendedModel,
        MaterialProducerDtoModel,
        MaterialProducerDtoExtendedModel,
        MaterialProducerCreateRequest,
        MaterialProducerCreateResponse,
        MaterialProducerUpdateRequest,
        MaterialProducerUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    MaterialProducerServiceExtendedModel toServiceExtendedModel(MaterialProducerCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    MaterialProducerServiceExtendedModel toUpdateServiceExtendedModel(MaterialProducerUpdateRequest source);
}
