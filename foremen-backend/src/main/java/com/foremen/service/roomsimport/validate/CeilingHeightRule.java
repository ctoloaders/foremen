package com.foremen.service.roomsimport.validate;

import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code CEILING_HEIGHT_OUT_OF_RANGE}: ceiling height outside {@code [min, max]} (default 1.8..6.0 mb). */
public class CeilingHeightRule implements ValidationRule {

    @Override
    public List<Warning> apply(ValidationContext ctx) {
        if (ctx.ceilingHeight() == null) {
            return List.of();
        }
        double h = ctx.ceilingHeight().doubleValue();
        double min = ctx.thresholds().ceilingMin();
        double max = ctx.thresholds().ceilingMax();
        if (h >= min && h <= max) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("value", ctx.ceilingHeight());
        params.put("min", min);
        params.put("max", max);
        return List.of(new Warning(WarningCodes.CEILING_HEIGHT_OUT_OF_RANGE, Severity.WARNING,
                ctx.tempId(), "ceilingHeight", params, null));
    }
}
