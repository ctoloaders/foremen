package com.foremen.service.dashboard.dataset.catalog;

import static com.foremen.service.dashboard.dataset.catalog.CatalogFields.*;

import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * FOR-10 dataset WORK_MATERIAL_CONSUMPTION: material norms per work item. Justification texts and
 * source document / url / reference are not exposed.
 */
@Component
public class WorkMaterialConsumptionDataset implements Dataset {

    @Override
    public String code() {
        return "WORK_MATERIAL_CONSUMPTION";
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("WORK_MATERIAL_CONSUMPTION");
    }

    @Override
    public Class<?> rootEntity() {
        return WorkMaterialConsumptionEntity.class;
    }

    @Override
    public List<FieldDescriptor> fields() {
        return concat(id(), str("workItem.code"), str("workItem.nameRU"), str("workItem.namePL"),
                enumField("branch", List.of("construction", "finishing")),
                enumField("consumptionBasis", List.of("PER_UNIT", "PER_ROOM")),
                num("normQty"), num("wastePct"), label("materialUnit"),
                label("constructionMaterialType"), label("finishingMaterialType"), str("sourceType"));
    }
}
