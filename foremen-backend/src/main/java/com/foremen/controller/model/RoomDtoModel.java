package com.foremen.controller.model;

import com.foremen.dao.model.RoomGeometry;

import java.math.BigDecimal;

/**
 * List/read DTO for a room. Exposes the resolved references ({@code projectId}/{@code projectName},
 * {@code roomTypeId}/{@code roomTypeName} — localized), the base numerics, the geometry, and each of
 * the five derived metrics as a {@link MeasureValueDto} ({@code value} + {@code source}).
 */
public record RoomDtoModel(
        Long id,
        Long projectId,
        String projectName,
        Long roomTypeId,
        String roomTypeName,
        String label,
        BigDecimal ceilingHeight,
        Integer internalCorners,
        Integer doorCount,
        Integer windowCount,
        BigDecimal doorHeight,
        BigDecimal doorWidth,
        BigDecimal windowHeight,
        BigDecimal windowWidth,
        RoomGeometry geometry,
        MeasureValueDto floorArea,
        MeasureValueDto wallArea,
        MeasureValueDto perimeter,
        MeasureValueDto doorArea,
        MeasureValueDto windowArea
) {}
