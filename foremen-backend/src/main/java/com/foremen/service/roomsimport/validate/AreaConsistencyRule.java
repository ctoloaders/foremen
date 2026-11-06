package com.foremen.service.roomsimport.validate;

import com.foremen.service.roomsimport.model.CalculatedMetrics;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code AREA_MISMATCH} / {@code PERIMETER_MISMATCH}: a stated value differs from the calculated one
 * by more than the tolerance (default 2 %). The calculated value is rounded to 2 decimals, so a
 * difference within that rounding is never a mismatch. The suggested fix is the calculated value.
 */
public class AreaConsistencyRule implements ValidationRule {

    private static final double EPSILON = 1e-6;
    /** Half a unit of the 2-decimal rounding applied to calculated metrics. */
    private static final double ROUNDING_SLACK = 0.005 + 1e-9;

    @Override
    public List<Warning> apply(ValidationContext ctx) {
        CalculatedMetrics calc = ctx.calculated();
        if (calc == null) {
            return List.of();
        }
        List<Warning> out = new ArrayList<>();
        check(ctx, "floorArea", WarningCodes.AREA_MISMATCH, calc.floorArea(), out);
        check(ctx, "perimeter", WarningCodes.PERIMETER_MISMATCH, calc.perimeter(), out);
        return out;
    }

    private static void check(ValidationContext ctx, String field, String code, BigDecimal calculated,
                              List<Warning> out) {
        Double stated = Num.toDouble(ctx.stated().get(field));
        if (stated == null || calculated == null) {
            return;
        }
        double c = calculated.doubleValue();
        double diff = Math.abs(stated - c);
        if (diff > ROUNDING_SLACK && diff / Math.max(c, EPSILON) > ctx.thresholds().areaTolerance()) {
            out.add(new Warning(code, Severity.WARNING, ctx.tempId(), field,
                    Map.of("stated", stated, "calculated", calculated),
                    Map.of(field, calculated)));
        }
    }
}
