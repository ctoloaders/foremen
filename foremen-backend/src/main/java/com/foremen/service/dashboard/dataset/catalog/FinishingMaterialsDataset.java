package com.foremen.service.dashboard.dataset.catalog;

import static com.foremen.service.dashboard.dataset.catalog.CatalogFields.*;

import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * FOR-10 dataset FINISHING_MATERIALS (features, link and photo are not exposed). The entity keeps
 * no currency, so money values use the company default currency.
 */
@Component
public class FinishingMaterialsDataset implements Dataset {

    @Override
    public String code() {
        return "FINISHING_MATERIALS";
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("MATERIALS_FINISHING");
    }

    @Override
    public Class<?> rootEntity() {
        return FinishingMaterialEntity.class;
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("createdDate");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return concat(id(), createdDate(), label("category"), label("material"), label("type"),
                label("producer"), str("model"), str("sku"), label("unit"),
                money("purchasePrice", null), money("retailNet", null), money("retailGross", null),
                money("costNet", null), bool("active"));
    }
}
