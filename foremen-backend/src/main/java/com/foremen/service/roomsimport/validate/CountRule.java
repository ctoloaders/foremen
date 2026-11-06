package com.foremen.service.roomsimport.validate;

import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import java.util.Map;
import java.util.Optional;

/** {@code ROOM_COUNT_MISMATCH}: an explicit room count in the prompt differs from the detected count. */
public final class CountRule {

    private CountRule() {}

    /** Project-level warning, or empty when no count was stated or it matches. */
    public static Optional<Warning> evaluate(Integer statedCount, int detectedCount) {
        if (statedCount == null || statedCount == detectedCount) {
            return Optional.empty();
        }
        return Optional.of(new Warning(WarningCodes.ROOM_COUNT_MISMATCH, Severity.WARNING, null, null,
                Map.of("stated", statedCount, "detected", detectedCount), null));
    }
}
