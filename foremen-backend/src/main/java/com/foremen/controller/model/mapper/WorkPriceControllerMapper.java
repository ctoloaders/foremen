package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceModel;
import org.mapstruct.Mapper;

/**
 * Controller mapper for the package-based {@code WorkPrice} aggregator.
 *
 * <p>Request/response shapes carry the {@code packagePrices} upsert list, and the list DTO carries the
 * {@code prices} map. All fields line up by name between the controller DTOs and the service models
 * ({@code WorkPriceServiceExtendedModel} = {@code (workItemId, packagePrices)}), so MapStruct maps them
 * automatically. The base interface's {@code toServiceExtendedModel} carries an {@code id}-ignore
 * mapping that no longer applies (the extended service model has no {@code id}), so both write
 * conversions are overridden here without it.
 */
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
    WorkPriceServiceExtendedModel toServiceExtendedModel(WorkPriceCreateRequest source);

    @Override
    WorkPriceServiceExtendedModel toUpdateServiceExtendedModel(WorkPriceUpdateRequest source);
}
