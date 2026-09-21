package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.EstimateLineCreateRequest;
import com.foremen.controller.model.EstimateLineCreateResponse;
import com.foremen.controller.model.EstimateLineDtoExtendedModel;
import com.foremen.controller.model.EstimateLineDtoModel;
import com.foremen.controller.model.EstimateLineUpdateRequest;
import com.foremen.controller.model.EstimateLineUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.EstimateLineServiceExtendedModel;
import com.foremen.service.model.EstimateLineServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Controller mapper for the estimate-line vertical (FOR-05-03, Requirement 2), following the FOR-04
 * {@code RoomControllerMapper} pattern: a thin MapStruct interface with all fields mapped by name,
 * only {@code id} (and, on update, {@code estimateId}) explicitly ignored on the write-side mappings.
 *
 * <p>{@code EstimateLineUpdateRequest} carries no {@code estimateId} (a line's owning estimate is
 * fixed at create time), so {@link #toUpdateServiceExtendedModel} leaves it unset — the service
 * update path never reassigns the owning estimate. The derived {@code quantity}/{@code valueNet} are
 * absent from every write-side request DTO, so they simply have nothing to map from (R2.6, R2.7,
 * R8.4); {@code EstimateRecomputeService} is the exclusive writer of those fields.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface EstimateLineControllerMapper extends ControllerToServiceMapper<
        EstimateLineServiceModel,
        EstimateLineServiceExtendedModel,
        EstimateLineDtoModel,
        EstimateLineDtoExtendedModel,
        EstimateLineCreateRequest,
        EstimateLineCreateResponse,
        EstimateLineUpdateRequest,
        EstimateLineUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    EstimateLineServiceExtendedModel toServiceExtendedModel(EstimateLineCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "estimateId", ignore = true)
    EstimateLineServiceExtendedModel toUpdateServiceExtendedModel(EstimateLineUpdateRequest source);

    @Override
    EstimateLineDtoModel toDto(EstimateLineServiceModel source);

    @Override
    EstimateLineDtoExtendedModel toExtendedDto(EstimateLineServiceExtendedModel source);

    @Override
    EstimateLineCreateResponse toCreateResponse(EstimateLineServiceExtendedModel source);

    @Override
    EstimateLineUpdateResponse toUpdateResponse(EstimateLineServiceExtendedModel source);
}
