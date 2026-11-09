package com.foremen.service.dashboard.dataset;

import com.foremen.service.dashboard.config.DatasetSchema;
import com.foremen.service.dashboard.query.QueryContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Code-defined data source for dashboard widgets (FOR-10 Requirement 6). A dataset is a Spring bean;
 * {@link DatasetRegistry} collects all of them and validates their paths at startup.
 */
public interface Dataset {

    /** Stable dataset code, e.g. {@code WORK_REPORTS}. */
    String code();

    /** Resource codes; Has_Access requires ANY operation on EACH of them. */
    Set<String> requiredResources();

    /** Field catalog backed by JPA paths (plus computed fields). */
    List<FieldDescriptor> fields();

    Class<?> rootEntity();

    /** JPA path to the owning project id; empty means company-wide. */
    default Optional<String> projectPath() {
        return Optional.empty();
    }

    /** JPA path to the owner user id. */
    default Optional<String> ownerPath() {
        return Optional.empty();
    }

    /** Role codes (e.g. WORKER) that only see their own rows. */
    default Set<String> ownerRestrictedRoles() {
        return Set.of();
    }

    /** Owner filter applies to every caller including ADMIN (e.g. NOTIFICATIONS). */
    default boolean alwaysOwn() {
        return false;
    }

    /** Field name the global date filter applies to by default. */
    default Optional<String> defaultDateField() {
        return Optional.empty();
    }

    default List<LookupField> lookups() {
        return List.of();
    }

    /**
     * Dataset-specific predicate ANDed with the generic ones (for example "exclude ignored rows").
     * {@code null} means none.
     */
    default Predicate extraPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return null;
    }

    /**
     * Rows without a project that the caller may still see (for example documents created by the
     * caller). ORed with the project-scope predicate for non-admin callers; {@code null} means none.
     */
    default Predicate nullProjectPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return null;
    }

    /** Fields plus lookups: everything addressable from a Widget_Config. */
    default List<FieldDescriptor> allFields() {
        List<FieldDescriptor> all = new ArrayList<>(fields());
        for (LookupField l : lookups()) {
            all.add(l.toDescriptor());
        }
        return List.copyOf(all);
    }

    /** Adapter to the validator's minimal view. */
    default DatasetSchema schema() {
        return new DatasetSchema(code(), projectPath().isPresent(),
                allFields().stream().map(FieldDescriptor::toSchemaField).toList());
    }
}
