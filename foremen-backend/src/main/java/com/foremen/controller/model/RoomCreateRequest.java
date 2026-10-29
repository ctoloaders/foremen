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
        BigDecimal windowArea,
        // FOR-05-02 (rooms matrix persistence): the per-door/window quantity + per-unit dimensions
        // the flat (geometry-less) matrix edits and from which door/window area is computed. Honored
        // only on the no-geometry (manual) path; when real geometry is supplied the backend derives
        // and overwrites them. Persisted so they survive save/reload (bug fix).
        Integer doorCount,
        Integer windowCount,
        BigDecimal doorHeight,
        BigDecimal doorWidth,
        BigDecimal windowHeight,
        BigDecimal windowWidth
) {}
