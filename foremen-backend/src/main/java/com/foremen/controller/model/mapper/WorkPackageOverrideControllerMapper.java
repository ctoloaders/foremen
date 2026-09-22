package com.foremen.controller.model.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.WorkPackageOverrideCreateRequest;
import com.foremen.controller.model.WorkPackageOverrideCreateResponse;
import com.foremen.controller.model.WorkPackageOverrideDtoExtendedModel;
import com.foremen.controller.model.WorkPackageOverrideDtoModel;
import com.foremen.controller.model.WorkPackageOverrideUpdateRequest;
import com.foremen.controller.model.WorkPackageOverrideUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.WorkPackageOverrideServiceExtendedModel;
import com.foremen.service.model.WorkPackageOverrideServiceModel;

/**
 * Controller mapper for a {@code (WorkItem, OfferPackage)} package membership + override formula
 * pair (FOR-05-04, Requirement 4, task 18.3). Request/response shapes carry
 * {@code workItemId}/{@code offerPackageId}/{@code member}/{@code overrideSourceText} directly,
 * lining up by name with {@code WorkPackageOverrideServiceExtendedModel}, so MapStruct maps them
 * automatically. Both write conversions are overridden without the base interface's {@code id}
 * -ignore mapping, mirroring {@code WorkPriceControllerMapper}/
 * {@code WorkVolumeFormulaControllerMapper}.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface WorkPackageOverrideControllerMapper extends ControllerToServiceMapper<
        WorkPackageOverrideServiceModel,
        WorkPackageOverrideServiceExtendedModel,
        WorkPackageOverrideDtoModel,
        WorkPackageOverrideDtoExtendedModel,
        WorkPackageOverrideCreateRequest,
        WorkPackageOverrideCreateResponse,
        WorkPackageOverrideUpdateRequest,
        WorkPackageOverrideUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    WorkPackageOverrideServiceExtendedModel toServiceExtendedModel(WorkPackageOverrideCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    WorkPackageOverrideServiceExtendedModel toUpdateServiceExtendedModel(WorkPackageOverrideUpdateRequest source);
}
