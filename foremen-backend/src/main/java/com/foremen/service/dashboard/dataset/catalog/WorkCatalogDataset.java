package com.foremen.service.dashboard.dataset.catalog;

import static com.foremen.service.dashboard.dataset.catalog.CatalogFields.*;

import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** FOR-10 dataset WORK_CATALOG: company-wide work items. */
@Component
public class WorkCatalogDataset implements Dataset {

    @Override
    public String code() {
        return "WORK_CATALOG";
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("WORK_CATALOG");
    }

    @Override
    public Class<?> rootEntity() {
        return WorkItemEntity.class;
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("createdDate");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return concat(id(), createdDate(), str("code"), str("nameRU"), str("namePL"),
                label("workCategory"), label("unit"), bool("active"));
    }
}
