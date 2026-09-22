package com.foremen.controller.model.mapper;

import org.mapstruct.Mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.WorkPriceCreateRequest;
import com.foremen.controller.model.WorkPriceCreateResponse;
import com.foremen.controller.model.WorkPriceDtoExtendedModel;
import com.foremen.controller.model.WorkPriceDtoModel;
import com.foremen.controller.model.WorkPriceUpdateRequest;
import com.foremen.controller.model.WorkPriceUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceModel;

/**
 * Controller mapper for the single-price {@code WorkPrice} row (FOR-05-04, Requirement 1).
 *
 * <p>Request/response shapes carry {@code workItemId}/{@code currencyId}/{@code netPrice} directly,
 * lining up by name with {@code WorkPriceServiceExtendedModel} (= {@code (workItemId, currencyId,
 * netPrice)}), so MapStruct maps them automatically. The base interface's {@code toServiceExtendedModel}
 * carries an {@code id}-ignore mapping that no longer applies (the extended service model has no
 * {@code id}), so both write conversions are overridden here without it.
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
