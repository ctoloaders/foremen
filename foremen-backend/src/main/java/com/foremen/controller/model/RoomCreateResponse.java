package com.foremen.controller.model;

import com.foremen.dao.model.MeasureSource;
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
        BigDecimal windowArea,
        BigDecimal narrowPlace,
        java.util.Map<String, String> dimensionFormulas,
        // FOR-05-02c: per-field MANUAL/CALCULATED flags of the gaps and opening sizes (nullable).
        MeasureSource wallGapSource,
        MeasureSource finishGapSource,
        MeasureSource doorHeightSource,
        MeasureSource doorWidthSource,
        MeasureSource windowHeightSource,
        MeasureSource windowWidthSource
) {}
