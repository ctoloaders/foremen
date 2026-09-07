package com.foremen.controller.model;

import com.foremen.dao.model.RoomGeometry;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Update payload for a room; same shape as {@link RoomCreateRequest}.
 *
 * <p>{@code projectId} and {@code roomTypeId} are mandatory references. When {@code geometry} is
 * present it is authoritative and the five derived metrics are recomputed and stamped
 * {@code CALCULATED}; the manual metric fields are only honored when {@code geometry} is absent,
 * where each supplied value is stamped {@code MANUAL}.
 */
public record RoomUpdateRequest(
        @NotNull Long projectId,
        @NotNull Long roomTypeId,
        String label,
        BigDecimal ceilingHeight,
        Integer internalCorners,
        RoomGeometry geometry,
        BigDecimal floorArea,
        BigDecimal wallArea,
        BigDecimal perimeter,
        BigDecimal doorArea,
        BigDecimal windowArea
) {}
