package com.foremen.service.dashboard.dataset.projects;

import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
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

/**
 * FOR-10 dataset {@code PROJECTS}: the projects the caller can access. Excludes geo coordinates and
 * Google place ids. {@code managerName} is a LOOKUP over the active project member with role MANAGER.
 */
@Component
public class ProjectsDataset implements Dataset {

    public static final String CODE = "PROJECTS";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("PROJECTS");
    }

    @Override
    public Class<?> rootEntity() {
        return ProjectEntity.class;
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("id");
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("startDate");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("name", FieldType.STRING),
                FieldDescriptor.of("address", FieldType.STRING),
                FieldDescriptor.of("area", FieldType.NUMBER),
                FieldDescriptor.of("status", FieldType.ENUM)
                        .withEnumValues(Arrays.stream(ProjectStatus.values()).map(Enum::name).toList()),
                FieldDescriptor.of("startDate", FieldType.DATE),
                FieldDescriptor.of("endDate", FieldType.DATE),
                FieldDescriptor.of("createdDate", FieldType.DATETIME));
    }

    @Override
    public List<LookupField> lookups() {
        return List.of(LookupField.of("managerName", FieldType.STRING, (cb, query, root) -> {
            Subquery<String> sub = query.subquery(String.class);
            Root<ProjectMemberEntity> m = sub.from(ProjectMemberEntity.class);
            sub.select(cb.greatest(m.get("user").<String>get("name")))
                    .where(cb.equal(m.get("projectId"), root.get("id")),
                            cb.equal(m.get("projectRole").get("code"), "MANAGER"),
                            cb.equal(m.get("assignmentStatus"), AssignmentStatus.ACTIVE));
            return sub;
        }));
    }
}
