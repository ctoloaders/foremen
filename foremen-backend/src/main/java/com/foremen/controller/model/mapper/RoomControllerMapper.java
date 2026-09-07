package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.MeasureValueDto;
import com.foremen.controller.model.RoomCreateRequest;
import com.foremen.controller.model.RoomCreateResponse;
import com.foremen.controller.model.RoomDtoExtendedModel;
import com.foremen.controller.model.RoomDtoModel;
import com.foremen.controller.model.RoomUpdateRequest;
import com.foremen.controller.model.RoomUpdateResponse;
import com.foremen.dao.model.MeasureSource;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.service.model.RoomServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;

/**
 * Controller mapper for the room vertical, following the FOR-04 {@code WorkPriceControllerMapper}
 * pattern.
 *
 * <p>Write mappings ({@link #toServiceExtendedModel} / {@link #toUpdateServiceExtendedModel}) copy
 * the flat metric values from the request; the source flags, counts and gaps are left unset and are
 * filled in by the {@code RoomService} pre-persist normalization step.
 *
 * <p>Read mappings ({@link #toDto} / {@link #toExtendedDto}) fold each flat
 * {@code value}/{@code *Source} pair from the service model into a composite {@link MeasureValueDto}
 * via the {@link #measure(BigDecimal, MeasureSource)} helper.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface RoomControllerMapper extends ControllerToServiceMapper<
        RoomServiceModel,
        RoomServiceExtendedModel,
        RoomDtoModel,
        RoomDtoExtendedModel,
        RoomCreateRequest,
        RoomCreateResponse,
        RoomUpdateRequest,
        RoomUpdateResponse> {

    /** Folds a flat metric value + source pair into a composite {@link MeasureValueDto}. */
    default MeasureValueDto measure(BigDecimal value, MeasureSource source) {
        if (value == null && source == null) {
            return null;
        }
        return new MeasureValueDto(value, source);
    }

    @Override
    @Mapping(target = "id", ignore = true)
    RoomServiceExtendedModel toServiceExtendedModel(RoomCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    RoomServiceExtendedModel toUpdateServiceExtendedModel(RoomUpdateRequest source);

    @Override
    @Mapping(target = "floorArea", expression = "java(measure(source.getFloorArea(), source.getFloorAreaSource()))")
    @Mapping(target = "wallArea", expression = "java(measure(source.getWallArea(), source.getWallAreaSource()))")
    @Mapping(target = "perimeter", expression = "java(measure(source.getPerimeter(), source.getPerimeterSource()))")
    @Mapping(target = "doorArea", expression = "java(measure(source.getDoorArea(), source.getDoorAreaSource()))")
    @Mapping(target = "windowArea", expression = "java(measure(source.getWindowArea(), source.getWindowAreaSource()))")
    RoomDtoModel toDto(RoomServiceModel source);

    @Override
    @Mapping(target = "floorArea", expression = "java(measure(source.getFloorArea(), source.getFloorAreaSource()))")
    @Mapping(target = "wallArea", expression = "java(measure(source.getWallArea(), source.getWallAreaSource()))")
    @Mapping(target = "perimeter", expression = "java(measure(source.getPerimeter(), source.getPerimeterSource()))")
    @Mapping(target = "doorArea", expression = "java(measure(source.getDoorArea(), source.getDoorAreaSource()))")
    @Mapping(target = "windowArea", expression = "java(measure(source.getWindowArea(), source.getWindowAreaSource()))")
    RoomDtoExtendedModel toExtendedDto(RoomServiceExtendedModel source);
}
