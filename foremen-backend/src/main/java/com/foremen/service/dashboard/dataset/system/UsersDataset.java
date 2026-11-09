package com.foremen.service.dashboard.dataset.system;

import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkerKind;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code USERS}: counts and groupings only; no name, e-mail, phone, NIP or contact person. */
@Component
public class UsersDataset extends SystemDataset {

    public UsersDataset() {
        super("USERS", "USERS", UserEntity.class, "createdDate", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("role.code", FieldType.STRING),
                enumField("status", UserStatus.class),
                FieldDescriptor.of("active", FieldType.BOOLEAN),
                FieldDescriptor.of("locale", FieldType.STRING),
                FieldDescriptor.of("workerType.code", FieldType.STRING),
                FieldDescriptor.of("workerType.nameRU", FieldType.STRING),
                FieldDescriptor.of("workerType.namePL", FieldType.STRING),
                enumField("workerKind", WorkerKind.class),
                FieldDescriptor.of("createdDate", FieldType.DATETIME)));
    }
}
