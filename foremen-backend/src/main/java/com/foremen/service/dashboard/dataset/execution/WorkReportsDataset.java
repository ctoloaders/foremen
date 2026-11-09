package com.foremen.service.dashboard.dataset.execution;

import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code WORK_REPORTS}: project-scoped; a WORKER only sees reports where they are the worker.
 * No note, rejection reason or revenue/price columns are exposed.
 */
@Component
public class WorkReportsDataset extends ExecutionDataset {

    public WorkReportsDataset() {
        super("WORK_REPORTS", "WORK_REPORTS", WorkReportEntity.class, "performedFrom", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("project.id", FieldType.ID),
                FieldDescriptor.of("project.name", FieldType.STRING),
                FieldDescriptor.of("workItem.code", FieldType.STRING),
                FieldDescriptor.of("workItem.nameRU", FieldType.STRING),
                FieldDescriptor.of("workItem.namePL", FieldType.STRING),
                FieldDescriptor.of("worker.id", FieldType.ID),
                FieldDescriptor.of("worker.name", FieldType.STRING),
                FieldDescriptor.of("author.name", FieldType.STRING),
                FieldDescriptor.of("performedFrom", FieldType.DATE),
                FieldDescriptor.of("performedTo", FieldType.DATE),
                enumField("status", WorkReportStatus.class),
                FieldDescriptor.of("approvedAt", FieldType.DATETIME),
                FieldDescriptor.of("approvedBy.name", FieldType.STRING)));
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("project.id");
    }

    @Override
    public Optional<String> ownerPath() {
        return Optional.of("worker.id");
    }

    @Override
    public Set<String> ownerRestrictedRoles() {
        return Set.of("WORKER");
    }
}
