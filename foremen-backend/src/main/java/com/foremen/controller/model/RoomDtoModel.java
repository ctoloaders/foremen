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
        MeasureValueDto windowArea,
        // FOR-05-02 Round 4: the room's fixed position within its project (read-only; changed only
        // via PUT /api/rooms/project/{projectId}/order).
        Integer sortOrder,
        // FOR-05-02 Round 5: narrow place (mb) + per-dimension value formulas (shown while editing).
        BigDecimal narrowPlace,
        java.util.Map<String, String> dimensionFormulas,
        // FOR-05-02 (R22): read-only marker, true for rooms created from an AI import.
        boolean aiImported,
        // FOR-05-02 (R10.4): number of attached media files (badge); no binary data.
        Integer mediaCount
) {}
