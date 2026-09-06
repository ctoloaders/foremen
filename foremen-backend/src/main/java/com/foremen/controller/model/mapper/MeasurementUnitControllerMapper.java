package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.MeasurementUnitServiceExtendedModel;
import com.foremen.service.model.MeasurementUnitServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface MeasurementUnitControllerMapper extends ControllerToServiceMapper<
        MeasurementUnitServiceModel,
        MeasurementUnitServiceExtendedModel,
        MeasurementUnitDtoModel,
        MeasurementUnitDtoExtendedModel,
        MeasurementUnitCreateRequest,
        MeasurementUnitCreateResponse,
        MeasurementUnitUpdateRequest,
        MeasurementUnitUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    MeasurementUnitServiceExtendedModel toServiceExtendedModel(MeasurementUnitCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    MeasurementUnitServiceExtendedModel toUpdateServiceExtendedModel(MeasurementUnitUpdateRequest source);
}
