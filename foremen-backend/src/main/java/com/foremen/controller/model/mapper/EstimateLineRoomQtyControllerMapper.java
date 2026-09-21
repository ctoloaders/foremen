package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.EstimateLineRoomQtyCreateRequest;
import com.foremen.controller.model.EstimateLineRoomQtyCreateResponse;
import com.foremen.controller.model.EstimateLineRoomQtyDtoExtendedModel;
import com.foremen.controller.model.EstimateLineRoomQtyDtoModel;
import com.foremen.controller.model.EstimateLineRoomQtyUpdateRequest;
import com.foremen.controller.model.EstimateLineRoomQtyUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.EstimateLineRoomQtyServiceExtendedModel;
import com.foremen.service.model.EstimateLineRoomQtyServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Controller mapper for the {@code EstimateLineRoomQty} vertical (FOR-05-03, Requirement 3),
 * following the {@code RoomControllerMapper} pattern: a plain MapStruct interface with no bespoke
 * folding logic since every field is a flat scalar/FK.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface EstimateLineRoomQtyControllerMapper extends ControllerToServiceMapper<
        EstimateLineRoomQtyServiceModel,
        EstimateLineRoomQtyServiceExtendedModel,
        EstimateLineRoomQtyDtoModel,
        EstimateLineRoomQtyDtoExtendedModel,
        EstimateLineRoomQtyCreateRequest,
        EstimateLineRoomQtyCreateResponse,
        EstimateLineRoomQtyUpdateRequest,
        EstimateLineRoomQtyUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    EstimateLineRoomQtyServiceExtendedModel toServiceExtendedModel(EstimateLineRoomQtyCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    EstimateLineRoomQtyServiceExtendedModel toUpdateServiceExtendedModel(EstimateLineRoomQtyUpdateRequest source);

    @Override
    EstimateLineRoomQtyDtoModel toDto(EstimateLineRoomQtyServiceModel source);

    @Override
    EstimateLineRoomQtyDtoExtendedModel toExtendedDto(EstimateLineRoomQtyServiceExtendedModel source);
}
