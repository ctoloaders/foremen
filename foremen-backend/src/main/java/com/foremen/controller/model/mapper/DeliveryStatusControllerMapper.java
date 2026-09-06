package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.DeliveryStatusServiceExtendedModel;
import com.foremen.service.model.DeliveryStatusServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface DeliveryStatusControllerMapper extends ControllerToServiceMapper<
        DeliveryStatusServiceModel,
        DeliveryStatusServiceExtendedModel,
        DeliveryStatusDtoModel,
        DeliveryStatusDtoExtendedModel,
        DeliveryStatusCreateRequest,
        DeliveryStatusCreateResponse,
        DeliveryStatusUpdateRequest,
        DeliveryStatusUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    DeliveryStatusServiceExtendedModel toServiceExtendedModel(DeliveryStatusCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    DeliveryStatusServiceExtendedModel toUpdateServiceExtendedModel(DeliveryStatusUpdateRequest source);
}
