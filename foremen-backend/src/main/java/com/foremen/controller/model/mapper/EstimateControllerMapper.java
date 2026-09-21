package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.EstimateCreateRequest;
import com.foremen.controller.model.EstimateCreateResponse;
import com.foremen.controller.model.EstimateDtoExtendedModel;
import com.foremen.controller.model.EstimateDtoModel;
import com.foremen.controller.model.EstimateUpdateRequest;
import com.foremen.controller.model.EstimateUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.EstimateServiceExtendedModel;
import com.foremen.service.model.EstimateServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Controller mapper for the estimate vertical (FOR-05-03, Requirement 1), following the FOR-04
 * {@code RoomControllerMapper} pattern: a thin MapStruct interface with all fields mapped by name,
 * only {@code id} explicitly ignored on the write-side mappings.
 *
 * <p>{@code EstimateUpdateRequest} carries no {@code estimateId}/{@code projectId} (the owning
 * project is fixed at create time, R1.1, R1.6), so {@link #toUpdateServiceExtendedModel} also leaves
 * {@code projectId} unset — the service update path never reassigns the owning project.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface EstimateControllerMapper extends ControllerToServiceMapper<
        EstimateServiceModel,
        EstimateServiceExtendedModel,
        EstimateDtoModel,
        EstimateDtoExtendedModel,
        EstimateCreateRequest,
        EstimateCreateResponse,
        EstimateUpdateRequest,
        EstimateUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    EstimateServiceExtendedModel toServiceExtendedModel(EstimateCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "projectId", ignore = true)
    EstimateServiceExtendedModel toUpdateServiceExtendedModel(EstimateUpdateRequest source);

    @Override
    EstimateDtoModel toDto(EstimateServiceModel source);

    @Override
    EstimateDtoExtendedModel toExtendedDto(EstimateServiceExtendedModel source);

    @Override
    EstimateCreateResponse toCreateResponse(EstimateServiceExtendedModel source);

    @Override
    EstimateUpdateResponse toUpdateResponse(EstimateServiceExtendedModel source);
}
