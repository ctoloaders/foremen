package com.foremen.controller.model.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.StandardWorkSetItemCreateRequest;
import com.foremen.controller.model.StandardWorkSetItemCreateResponse;
import com.foremen.controller.model.StandardWorkSetItemDtoExtendedModel;
import com.foremen.controller.model.StandardWorkSetItemDtoModel;
import com.foremen.controller.model.StandardWorkSetItemUpdateRequest;
import com.foremen.controller.model.StandardWorkSetItemUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.StandardWorkSetItemServiceExtendedModel;
import com.foremen.service.model.StandardWorkSetItemServiceModel;

@Mapper(config = ForemenMapperConfig.class)
public interface StandardWorkSetItemControllerMapper extends ControllerToServiceMapper<
        StandardWorkSetItemServiceModel,
        StandardWorkSetItemServiceExtendedModel,
        StandardWorkSetItemDtoModel,
        StandardWorkSetItemDtoExtendedModel,
        StandardWorkSetItemCreateRequest,
        StandardWorkSetItemCreateResponse,
        StandardWorkSetItemUpdateRequest,
        StandardWorkSetItemUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    StandardWorkSetItemServiceExtendedModel toServiceExtendedModel(StandardWorkSetItemCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    StandardWorkSetItemServiceExtendedModel toUpdateServiceExtendedModel(StandardWorkSetItemUpdateRequest source);
}
