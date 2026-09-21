package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.EstimateLinePackagePriceCreateRequest;
import com.foremen.controller.model.EstimateLinePackagePriceCreateResponse;
import com.foremen.controller.model.EstimateLinePackagePriceDtoExtendedModel;
import com.foremen.controller.model.EstimateLinePackagePriceDtoModel;
import com.foremen.controller.model.EstimateLinePackagePriceUpdateRequest;
import com.foremen.controller.model.EstimateLinePackagePriceUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.EstimateLinePackagePriceServiceExtendedModel;
import com.foremen.service.model.EstimateLinePackagePriceServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Controller mapper for the {@code EstimateLinePackagePrice} vertical (FOR-05-03, Requirements 4,
 * 5), following the {@code RoomControllerMapper} pattern.
 *
 * <p>The create/update request DTOs expose ONLY the client-owned discount fields
 * ({@code discountKind}, {@code discountValue}) plus the FKs; they carry no
 * {@code originalUnitPrice}/{@code unitPrice}/{@code unpriced} properties at all, so those
 * snapshot/derived fields are simply left unset on the mapped
 * {@link EstimateLinePackagePriceServiceExtendedModel} — consistent with the service-layer mapper
 * (task 5.2), which ignores them inbound regardless.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface EstimateLinePackagePriceControllerMapper extends ControllerToServiceMapper<
        EstimateLinePackagePriceServiceModel,
        EstimateLinePackagePriceServiceExtendedModel,
        EstimateLinePackagePriceDtoModel,
        EstimateLinePackagePriceDtoExtendedModel,
        EstimateLinePackagePriceCreateRequest,
        EstimateLinePackagePriceCreateResponse,
        EstimateLinePackagePriceUpdateRequest,
        EstimateLinePackagePriceUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    EstimateLinePackagePriceServiceExtendedModel toServiceExtendedModel(EstimateLinePackagePriceCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    EstimateLinePackagePriceServiceExtendedModel toUpdateServiceExtendedModel(EstimateLinePackagePriceUpdateRequest source);

    @Override
    EstimateLinePackagePriceDtoModel toDto(EstimateLinePackagePriceServiceModel source);

    @Override
    EstimateLinePackagePriceDtoExtendedModel toExtendedDto(EstimateLinePackagePriceServiceExtendedModel source);
}
