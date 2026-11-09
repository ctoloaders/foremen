package com.foremen.service.dashboard.dataset.projects;

import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.dashboard.dataset.LookupField;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** FOR-10 dataset {@code PROJECT_MEMBERS}: team assignments (no e-mail / phone / tags). */
@Component
public class ProjectMembersDataset implements Dataset {

    public static final String CODE = "PROJECT_MEMBERS";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("PROJECT_MEMBERS");
    }

    @Override
    public Class<?> rootEntity() {
        return ProjectMemberEntity.class;
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("projectId");
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("createdDate");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("projectId", FieldType.ID),
                FieldDescriptor.of("user.name", FieldType.STRING),
                FieldDescriptor.of("projectRole.code", FieldType.STRING),
                FieldDescriptor.of("workerType.nameRU", FieldType.STRING),
                FieldDescriptor.of("workerType.namePL", FieldType.STRING),
                FieldDescriptor.of("assignmentStatus", FieldType.ENUM)
                        .withEnumValues(Arrays.stream(AssignmentStatus.values()).map(Enum::name).toList()),
                FieldDescriptor.of("createdDate", FieldType.DATETIME));
    }

    @Override
    public List<LookupField> lookups() {
        return List.of(LookupField.of("projectName", FieldType.STRING, (cb, query, root) -> {
            Subquery<String> sub = query.subquery(String.class);
            Root<ProjectEntity> p = sub.from(ProjectEntity.class);
            sub.select(p.<String>get("name")).where(cb.equal(p.get("id"), root.get("projectId")));
            return sub;
        }));
    }
}
