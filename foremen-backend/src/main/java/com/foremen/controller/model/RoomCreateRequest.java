package com.foremen.controller.model;

import com.foremen.dao.model.RoomGeometry;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Create payload for a room.
 *
 * <p>{@code projectId} and {@code roomTypeId} are mandatory references. The optional
 * {@code geometry}, when present, is authoritative: the backend derives the five metrics
 * ({@code floorArea}/{@code wallArea}/{@code perimeter}/{@code doorArea}/{@code windowArea}) from
 * it and stamps them {@code CALCULATED}, ignoring any directly supplied manual values. The manual
 * metric fields are only honored when {@code geometry} is absent, where each supplied value is
 * stamped {@code MANUAL}.
 */
public record RoomCreateRequest(
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
