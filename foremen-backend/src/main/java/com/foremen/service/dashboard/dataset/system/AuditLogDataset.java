package com.foremen.service.dashboard.dataset.system;

import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code AUDIT_LOG}: who did what and when; the before/after snapshots are never exposed. */
@Component
public class AuditLogDataset extends SystemDataset {

    public AuditLogDataset() {
        super("AUDIT_LOG", "AUDIT", AuditLogEntity.class, "performedAt", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("entityClass", FieldType.STRING),
                FieldDescriptor.of("entityId", FieldType.ID),
                FieldDescriptor.of("operation", FieldType.STRING),
                FieldDescriptor.of("performedBy", FieldType.STRING),
                FieldDescriptor.of("performedAt", FieldType.DATETIME)));
    }
}
