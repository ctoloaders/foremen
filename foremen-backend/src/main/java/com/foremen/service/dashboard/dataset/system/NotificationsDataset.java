package com.foremen.service.dashboard.dataset.system;

import com.foremen.dao.model.NotificationEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@code NOTIFICATIONS}: the caller's own notifications only (owner filter applies to every role,
 * including ADMIN). Body and deep link are not exposed.
 */
@Component
public class NotificationsDataset extends SystemDataset {

    public NotificationsDataset() {
        super("NOTIFICATIONS", "NOTIFICATIONS", NotificationEntity.class, "createdDate", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("type", FieldType.STRING),
                FieldDescriptor.of("read", FieldType.BOOLEAN),
                FieldDescriptor.of("createdDate", FieldType.DATETIME)));
    }

    @Override
    public Optional<String> ownerPath() {
        return Optional.of("recipient.id");
    }

    @Override
    public boolean alwaysOwn() {
        return true;
    }
}
