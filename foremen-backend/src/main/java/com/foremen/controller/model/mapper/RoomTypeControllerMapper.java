package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.RoomTypeServiceExtendedModel;
import com.foremen.service.model.RoomTypeServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface RoomTypeControllerMapper extends ControllerToServiceMapper<
        RoomTypeServiceModel,
        RoomTypeServiceExtendedModel,
        RoomTypeDtoModel,
        RoomTypeDtoExtendedModel,
        RoomTypeCreateRequest,
        RoomTypeCreateResponse,
        RoomTypeUpdateRequest,
        RoomTypeUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    RoomTypeServiceExtendedModel toServiceExtendedModel(RoomTypeCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    RoomTypeServiceExtendedModel toUpdateServiceExtendedModel(RoomTypeUpdateRequest source);
}
