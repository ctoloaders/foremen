package com.foremen.service.dashboard.dataset.system;

import com.foremen.dao.model.RoleEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code ROLES}: role catalog (code, names, system flag). */
@Component
public class RolesDataset extends SystemDataset {

    public RolesDataset() {
        super("ROLES", "ROLES", RoleEntity.class, null, List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("code", FieldType.STRING),
                FieldDescriptor.of("nameRU", FieldType.STRING),
                FieldDescriptor.of("namePL", FieldType.STRING),
                FieldDescriptor.of("system", FieldType.BOOLEAN)));
    }
}
