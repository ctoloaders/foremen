package com.foremen.service.model;

import java.time.LocalDateTime;
import java.util.Map;

public record AuditServiceModel(
        Long id,
        String entityClass,
        Long entityId,
        String operation,
        String performedBy,
        LocalDateTime performedAt,
        Map<String, Object> snapshotBefore,
        Map<String, Object> snapshotAfter
) {
}
