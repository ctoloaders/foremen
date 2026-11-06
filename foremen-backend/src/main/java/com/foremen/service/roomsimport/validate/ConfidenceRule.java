package com.foremen.service.roomsimport.validate;

import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code LOW_CONFIDENCE}: room confidence below the configured threshold (default 0.6). */
public class ConfidenceRule implements ValidationRule {

    @Override
    public List<Warning> apply(ValidationContext ctx) {
        double threshold = ctx.thresholds().lowConfidence();
        if (ctx.confidence() >= threshold) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("confidence", Double.isFinite(ctx.confidence()) ? ctx.confidence() : 0.0);
        params.put("threshold", threshold);
        return List.of(new Warning(WarningCodes.LOW_CONFIDENCE, Severity.WARNING, ctx.tempId(),
                null, params, null));
    }
}
