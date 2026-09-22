package com.foremen.controller.model.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.WorkVolumeFormulaCreateRequest;
import com.foremen.controller.model.WorkVolumeFormulaCreateResponse;
import com.foremen.controller.model.WorkVolumeFormulaDtoExtendedModel;
import com.foremen.controller.model.WorkVolumeFormulaDtoModel;
import com.foremen.controller.model.WorkVolumeFormulaUpdateRequest;
import com.foremen.controller.model.WorkVolumeFormulaUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.WorkVolumeFormulaServiceExtendedModel;
import com.foremen.service.model.WorkVolumeFormulaServiceModel;

/**
 * Controller mapper for a work item's default volume formula (FOR-05-04, Requirement 2, task
 * 18.3). Request/response shapes carry {@code workItemId}/{@code sourceText} directly, lining up
 * by name with {@code WorkVolumeFormulaServiceExtendedModel}, so MapStruct maps them
 * automatically. The base interface's {@code toServiceExtendedModel} carries an {@code id}-ignore
 * mapping that no longer applies (the extended service model's {@code id} is set only on read),
 * so both write conversions are overridden here without it, mirroring
 * {@code WorkPriceControllerMapper}.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface WorkVolumeFormulaControllerMapper extends ControllerToServiceMapper<
        WorkVolumeFormulaServiceModel,
        WorkVolumeFormulaServiceExtendedModel,
        WorkVolumeFormulaDtoModel,
        WorkVolumeFormulaDtoExtendedModel,
        WorkVolumeFormulaCreateRequest,
        WorkVolumeFormulaCreateResponse,
        WorkVolumeFormulaUpdateRequest,
        WorkVolumeFormulaUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    WorkVolumeFormulaServiceExtendedModel toServiceExtendedModel(WorkVolumeFormulaCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    WorkVolumeFormulaServiceExtendedModel toUpdateServiceExtendedModel(WorkVolumeFormulaUpdateRequest source);
}
