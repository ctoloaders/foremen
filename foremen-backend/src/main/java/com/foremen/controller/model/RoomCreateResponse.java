package com.foremen.controller.model;

import com.foremen.dao.model.RoomGeometry;

import java.math.BigDecimal;

/**
 * Create response for a room, mirroring the persisted extended service model (flat metric values
 * plus the normalized geometry and counts/gaps).
 */
public record RoomCreateResponse(
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
        BigDecimal floorArea,
        BigDecimal wallArea,
        BigDecimal perimeter,
        BigDecimal doorArea,
        BigDecimal windowArea
) {}
