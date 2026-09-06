package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.CurrencyServiceExtendedModel;
import com.foremen.service.model.CurrencyServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface CurrencyControllerMapper extends ControllerToServiceMapper<
        CurrencyServiceModel,
        CurrencyServiceExtendedModel,
        CurrencyDtoModel,
        CurrencyDtoExtendedModel,
        CurrencyCreateRequest,
        CurrencyCreateResponse,
        CurrencyUpdateRequest,
        CurrencyUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    CurrencyServiceExtendedModel toServiceExtendedModel(CurrencyCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    CurrencyServiceExtendedModel toUpdateServiceExtendedModel(CurrencyUpdateRequest source);
}
