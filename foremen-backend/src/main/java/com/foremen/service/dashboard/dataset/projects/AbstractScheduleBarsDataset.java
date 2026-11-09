package com.foremen.service.dashboard.dataset.projects;

import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;

import java.util.List;
import java.util.Optional;

/**
 * Shared field catalog of the schedule-bar datasets. Bars store half-day offsets from the schedule
 * start and the schedule has no absolute anchor date, so there is no date field (numeric
 * aggregations only).
 */
abstract class AbstractScheduleBarsDataset implements Dataset {

    @Override
    public Class<?> rootEntity() {
        return ProjectScheduleBarEntity.class;
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("schedule.project.id");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("schedule.project.id", FieldType.ID),
                FieldDescriptor.of("schedule.project.name", FieldType.STRING),
                FieldDescriptor.of("workCategory.nameRU", FieldType.STRING),
                FieldDescriptor.of("workCategory.namePL", FieldType.STRING),
                FieldDescriptor.of("projectMember.user.name", FieldType.STRING),
                FieldDescriptor.of("startHalfDays", FieldType.NUMBER),
                FieldDescriptor.of("durationHalfDays", FieldType.NUMBER));
    }
}
