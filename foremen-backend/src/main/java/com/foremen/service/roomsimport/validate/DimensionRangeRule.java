package com.foremen.service.roomsimport.validate;

import com.foremen.controller.model.RoomCreateRequest;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code DIMENSION_OUT_OF_RANGE}: a numeric value (in the room request or in {@code stated}) that the
 * manual save path would reject: outside {@code [0, 9999999999.99]}, or a negative count.
 */
public class DimensionRangeRule implements ValidationRule {

    static final BigDecimal MIN = BigDecimal.ZERO;
    static final BigDecimal MAX = new BigDecimal("9999999999.99");

    @Override
    public List<Warning> apply(ValidationContext ctx) {
        List<Warning> out = new ArrayList<>();
        RoomCreateRequest r = ctx.room();
        if (r != null) {
            decimal(ctx, "ceilingHeight", r.ceilingHeight(), out);
            decimal(ctx, "floorArea", r.floorArea(), out);
            decimal(ctx, "wallArea", r.wallArea(), out);
            decimal(ctx, "perimeter", r.perimeter(), out);
            decimal(ctx, "doorArea", r.doorArea(), out);
            decimal(ctx, "windowArea", r.windowArea(), out);
            decimal(ctx, "doorHeight", r.doorHeight(), out);
            decimal(ctx, "doorWidth", r.doorWidth(), out);
            decimal(ctx, "windowHeight", r.windowHeight(), out);
            decimal(ctx, "windowWidth", r.windowWidth(), out);
            decimal(ctx, "narrowPlace", r.narrowPlace(), out);
            count(ctx, "internalCorners", r.internalCorners(), out);
            count(ctx, "doorCount", r.doorCount(), out);
            count(ctx, "windowCount", r.windowCount(), out);
        }
        for (Map.Entry<String, Object> e : ctx.stated().entrySet()) {
            if (e.getValue() instanceof Number n) {
                double d = n.doubleValue();
                if (!Double.isFinite(d) || d < 0 || d > MAX.doubleValue()) {
                    out.add(warn(ctx, "stated." + e.getKey(), e.getValue()));
                }
            }
        }
        return out;
    }

    private static void decimal(ValidationContext ctx, String field, BigDecimal v, List<Warning> out) {
        if (v != null && (v.compareTo(MIN) < 0 || v.compareTo(MAX) > 0)) {
            out.add(warn(ctx, field, v));
        }
    }

    private static void count(ValidationContext ctx, String field, Integer v, List<Warning> out) {
        if (v != null && v < 0) {
            out.add(warn(ctx, field, v));
        }
    }

    private static Warning warn(ValidationContext ctx, String field, Object value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("value", value);
        params.put("min", MIN);
        params.put("max", MAX);
        return new Warning(WarningCodes.DIMENSION_OUT_OF_RANGE, Severity.WARNING, ctx.tempId(), field, params, null);
    }
}
