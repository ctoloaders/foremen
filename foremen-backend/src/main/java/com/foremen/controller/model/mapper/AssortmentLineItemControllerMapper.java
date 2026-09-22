package com.foremen.controller.model.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.AssortmentLineItemCreateRequest;
import com.foremen.controller.model.AssortmentLineItemCreateResponse;
import com.foremen.controller.model.AssortmentLineItemDtoExtendedModel;
import com.foremen.controller.model.AssortmentLineItemDtoModel;
import com.foremen.controller.model.AssortmentLineItemUpdateRequest;
import com.foremen.controller.model.AssortmentLineItemUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.AssortmentLineItemServiceExtendedModel;
import com.foremen.service.model.AssortmentLineItemServiceModel;

/**
 * Controller mapper for {@link AssortmentLineItemDtoModel} (FOR-05-04, Requirement 6.1, 6.2,
 * task 18.2). Request/response shapes carry {@code assortmentGroupId}/{@code offerPackageId}/
 * {@code nameRU}/{@code namePL}/{@code minPrice}/{@code avgPrice}/{@code maxPrice}/
 * {@code qtyRef50}/{@code typicalProductId} directly, lining up by name with
 * {@code AssortmentLineItemServiceExtendedModel}, so MapStruct maps them automatically. The base
 * interface's {@code toServiceExtendedModel} carries an {@code id}-ignore mapping that no longer
 * applies (the extended service model's {@code id} is set only on read), so both write
 * conversions are overridden here without it, mirroring
 * {@code WorkVolumeFormulaControllerMapper}/{@code AssortmentGroupControllerMapper}.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface AssortmentLineItemControllerMapper extends ControllerToServiceMapper<
        AssortmentLineItemServiceModel,
        AssortmentLineItemServiceExtendedModel,
        AssortmentLineItemDtoModel,
        AssortmentLineItemDtoExtendedModel,
        AssortmentLineItemCreateRequest,
        AssortmentLineItemCreateResponse,
        AssortmentLineItemUpdateRequest,
        AssortmentLineItemUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    AssortmentLineItemServiceExtendedModel toServiceExtendedModel(AssortmentLineItemCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    AssortmentLineItemServiceExtendedModel toUpdateServiceExtendedModel(AssortmentLineItemUpdateRequest source);
}
