package com.foremen.service.roomsimport.model;

import java.math.BigDecimal;

/** Unmodified output of {@code RoomCalculationService} for a draft room. */
public record CalculatedMetrics(
        BigDecimal floorArea,
        BigDecimal wallArea,
        BigDecimal perimeter,
        BigDecimal doorArea,
        BigDecimal windowArea,
        Integer doorCount,
        Integer windowCount) {}
