package com.foremen.service.dashboard.dataset.catalog;

import static com.foremen.service.dashboard.dataset.catalog.CatalogFields.*;

import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** FOR-10 dataset CONSTRUCTION_MATERIALS (website and image are not exposed). */
@Component
public class ConstructionMaterialsDataset implements Dataset {

    @Override
    public String code() {
        return "CONSTRUCTION_MATERIALS";
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("MATERIALS_CONSTRUCTION");
    }

    @Override
    public Class<?> rootEntity() {
        return ConstructionMaterialEntity.class;
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("createdDate");
    }

    @Override
    public List<FieldDescriptor> fields() {
        String cur = "currency.code";
        return concat(id(), createdDate(), str("nameRU"), str("namePL"), label("type"),
                label("producer"), label("seller"), label("unit"), str(cur),
                money("purchasePrice", cur), money("retailNet", cur), money("retailGross", cur),
                money("costNet", cur), bool("active"));
    }
}
