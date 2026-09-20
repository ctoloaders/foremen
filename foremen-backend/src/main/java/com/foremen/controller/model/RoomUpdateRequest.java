package com.foremen.controller.model;

import java.math.BigDecimal;

import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.RoomGeometry;

import jakarta.validation.constraints.NotNull;

/**
 * Update payload for a room; same shape as {@link RoomCreateRequest} plus optional per-metric source
 * flags.
 *
 * <p>{@code projectId} and {@code roomTypeId} are mandatory references. When {@code geometry} is
 * absent, each supplied metric value is stamped {@code MANUAL}. When {@code geometry} is present it
 * is authoritative and the five derived metrics are recomputed and stamped {@code CALCULATED} —
 * <em>except</em> that a caller may request a per-field manual override (FOR-05-02, Requirement 5.2)
 * by sending, for a given metric, an explicit value together with its {@code *Source} set to
 * {@code MANUAL}: that single field is kept as {@code MANUAL} while the others stay
 * {@code CALCULATED} from geometry. A {@code *Source} left {@code null} (or {@code CALCULATED}) is
 * not an override.
 */
public record RoomUpdateRequest(
        @NotNull Long projectId,
        @NotNull Long roomTypeId,
        String label,
        BigDecimal ceilingHeight,
        Integer internalCorners,
        RoomGeometry geometry,
        BigDecimal floorArea,
        MeasureSource floorAreaSource,
        BigDecimal wallArea,
        MeasureSource wallAreaSource,
        BigDecimal perimeter,
        MeasureSource perimeterSource,
        BigDecimal doorArea,
        MeasureSource doorAreaSource,
        BigDecimal windowArea,
        MeasureSource windowAreaSource
) {}
