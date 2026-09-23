package com.foremen.controller.model.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.AssortmentPositionCreateRequest;
import com.foremen.controller.model.AssortmentPositionCreateResponse;
import com.foremen.controller.model.AssortmentPositionDtoExtendedModel;
import com.foremen.controller.model.AssortmentPositionDtoModel;
import com.foremen.controller.model.AssortmentPositionUpdateRequest;
import com.foremen.controller.model.AssortmentPositionUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.AssortmentPositionServiceExtendedModel;
import com.foremen.service.model.AssortmentPositionServiceModel;

/**
 * Controller mapper for {@link AssortmentPositionDtoModel} (FOR-05-04-UI assortment rework).
 * Request/response shapes carry {@code assortmentGroupId}/{@code materialTypeId}/
 * {@code sortOrder} directly, lining up by name with {@code AssortmentPositionServiceExtendedModel},
 * so MapStruct maps them automatically. The base interface's {@code toServiceExtendedModel}
 * carries an {@code id}-ignore mapping that no longer applies (the extended service model's
 * {@code id} is set only on read), so both write conversions are overridden here without it,
 * mirroring {@code AssortmentLineItemControllerMapper}/{@code AssortmentGroupControllerMapper}.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface AssortmentPositionControllerMapper extends ControllerToServiceMapper<
        AssortmentPositionServiceModel,
        AssortmentPositionServiceExtendedModel,
        AssortmentPositionDtoModel,
        AssortmentPositionDtoExtendedModel,
        AssortmentPositionCreateRequest,
        AssortmentPositionCreateResponse,
        AssortmentPositionUpdateRequest,
        AssortmentPositionUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    AssortmentPositionServiceExtendedModel toServiceExtendedModel(AssortmentPositionCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    AssortmentPositionServiceExtendedModel toUpdateServiceExtendedModel(AssortmentPositionUpdateRequest source);
}
