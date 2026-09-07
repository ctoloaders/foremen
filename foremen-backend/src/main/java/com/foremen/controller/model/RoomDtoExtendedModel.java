package com.foremen.controller.model;

import com.foremen.dao.model.RoomGeometry;

import java.math.BigDecimal;

/**
 * Extended DTO for a room, mapped from the extended service model. Exposes the reference ids, the
 * base numerics, the geometry, the derived counts/gaps, and each of the five derived metrics as a
 * {@link MeasureValueDto} ({@code value} + {@code source}).
 */
public record RoomDtoExtendedModel(
        Long id,
        Long projectId,
        Long roomTypeId,
        String label,
        BigDecimal ceilingHeight,
        Integer internalCorners,
        Integer doorCount,
        Integer windowCount,
        BigDecimal doorHeight,
        BigDecimal doorWidth,
        BigDecimal windowHeight,
        BigDecimal windowWidth,
        BigDecimal wallGap,
        BigDecimal finishGap,
        RoomGeometry geometry,
        MeasureValueDto floorArea,
        MeasureValueDto wallArea,
        MeasureValueDto perimeter,
        MeasureValueDto doorArea,
        MeasureValueDto windowArea
) {}
