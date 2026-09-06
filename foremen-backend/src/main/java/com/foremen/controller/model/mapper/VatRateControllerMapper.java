package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.VatRateServiceExtendedModel;
import com.foremen.service.model.VatRateServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface VatRateControllerMapper extends ControllerToServiceMapper<
        VatRateServiceModel,
        VatRateServiceExtendedModel,
        VatRateDtoModel,
        VatRateDtoExtendedModel,
        VatRateCreateRequest,
        VatRateCreateResponse,
        VatRateUpdateRequest,
        VatRateUpdateResponse> {

    // The service model's boolean getter isDefault() exposes the MapStruct property name "default",
    // whereas the DTO record component is "isDefault"; map explicitly so the flag survives on list rows.
    @Override
    @Mapping(target = "isDefault", source = "default")
    VatRateDtoModel toDto(VatRateServiceModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "isDefault", expression = "java(source.isDefault() != null && source.isDefault())")
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    VatRateServiceExtendedModel toServiceExtendedModel(VatRateCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "isDefault", expression = "java(source.isDefault() != null && source.isDefault())")
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    VatRateServiceExtendedModel toUpdateServiceExtendedModel(VatRateUpdateRequest source);
}
