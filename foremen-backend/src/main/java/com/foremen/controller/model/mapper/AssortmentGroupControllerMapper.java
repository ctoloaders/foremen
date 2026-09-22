package com.foremen.controller.model.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.AssortmentGroupCreateRequest;
import com.foremen.controller.model.AssortmentGroupCreateResponse;
import com.foremen.controller.model.AssortmentGroupDtoExtendedModel;
import com.foremen.controller.model.AssortmentGroupDtoModel;
import com.foremen.controller.model.AssortmentGroupUpdateRequest;
import com.foremen.controller.model.AssortmentGroupUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.AssortmentGroupServiceExtendedModel;
import com.foremen.service.model.AssortmentGroupServiceModel;

/**
 * Controller mapper for {@link AssortmentGroupDtoModel} (FOR-05-04, Requirement 6.1, task 18.2).
 * Request/response shapes carry {@code nameRU}/{@code namePL}/{@code sortOrder} directly, lining
 * up by name with {@code AssortmentGroupServiceExtendedModel}, so MapStruct maps them
 * automatically. The base interface's {@code toServiceExtendedModel} carries an {@code id}-ignore
 * mapping that no longer applies (the extended service model's {@code id} is set only on read),
 * so both write conversions are overridden here without it, mirroring
 * {@code WorkVolumeFormulaControllerMapper}.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface AssortmentGroupControllerMapper extends ControllerToServiceMapper<
        AssortmentGroupServiceModel,
        AssortmentGroupServiceExtendedModel,
        AssortmentGroupDtoModel,
        AssortmentGroupDtoExtendedModel,
        AssortmentGroupCreateRequest,
        AssortmentGroupCreateResponse,
        AssortmentGroupUpdateRequest,
        AssortmentGroupUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    AssortmentGroupServiceExtendedModel toServiceExtendedModel(AssortmentGroupCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    AssortmentGroupServiceExtendedModel toUpdateServiceExtendedModel(AssortmentGroupUpdateRequest source);
}
