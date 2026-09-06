package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface WorkPriceControllerMapper extends ControllerToServiceMapper<
        WorkPriceServiceModel,
        WorkPriceServiceExtendedModel,
        WorkPriceDtoModel,
        WorkPriceDtoExtendedModel,
        WorkPriceCreateRequest,
        WorkPriceCreateResponse,
        WorkPriceUpdateRequest,
        WorkPriceUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    WorkPriceServiceExtendedModel toServiceExtendedModel(WorkPriceCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    WorkPriceServiceExtendedModel toUpdateServiceExtendedModel(WorkPriceUpdateRequest source);
}
