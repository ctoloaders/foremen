package com.foremen.service.roomsimport.model;

import java.util.Map;

/**
 * A draft-level or room-level finding. {@code suggestedFix} is an optional patch
 * ({@code field -> value}) the caller may offer to apply.
 */
public record Warning(
        String code,
        Severity severity,
        String roomTempId,
        String field,
        Map<String, Object> params,
        Map<String, Object> suggestedFix) {}
