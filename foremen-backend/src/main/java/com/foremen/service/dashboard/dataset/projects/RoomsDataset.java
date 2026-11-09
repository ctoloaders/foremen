package com.foremen.service.dashboard.dataset.projects;

import com.foremen.dao.model.RoomEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** FOR-10 dataset {@code ROOMS}: project rooms with measurements (no geometry / formulas). */
@Component
public class RoomsDataset implements Dataset {

    public static final String CODE = "ROOMS";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("ROOMS");
    }

    @Override
    public Class<?> rootEntity() {
        return RoomEntity.class;
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("project.id");
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("createdDate");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("project.id", FieldType.ID),
                FieldDescriptor.of("project.name", FieldType.STRING),
                FieldDescriptor.of("label", FieldType.STRING),
                FieldDescriptor.of("roomType.nameRU", FieldType.STRING),
                FieldDescriptor.of("roomType.namePL", FieldType.STRING),
                FieldDescriptor.of("floorArea", FieldType.NUMBER),
                FieldDescriptor.of("wallArea", FieldType.NUMBER),
                FieldDescriptor.of("perimeter", FieldType.NUMBER),
                FieldDescriptor.of("ceilingHeight", FieldType.NUMBER),
                FieldDescriptor.of("doorCount", FieldType.NUMBER),
                FieldDescriptor.of("windowCount", FieldType.NUMBER),
                FieldDescriptor.of("aiImported", FieldType.BOOLEAN),
                FieldDescriptor.of("createdDate", FieldType.DATETIME));
    }
}
