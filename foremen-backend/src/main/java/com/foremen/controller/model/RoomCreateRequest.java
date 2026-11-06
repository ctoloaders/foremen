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
        BigDecimal windowWidth,
        // FOR-05-02 Round 5: narrow place (mb) + per-dimension value formulas, e.g.
        // {"ceilingHeight": "=2,5+0,2"}. A formula wins over the submitted number (evaluated here).
        BigDecimal narrowPlace,
        java.util.Map<String, String> dimensionFormulas,
        // FOR-05-02 (R9.4, R22.2): optional, additive. Draft media of an AI import to attach to the
        // created room, and the import job that produced the room (derives the read-only aiImported
        // marker after verification). Both are create-only and soft-failing.
        java.util.List<com.foremen.service.roomsimport.model.ImportMediaRef> importMedia,
        Long importJobId
) {}
