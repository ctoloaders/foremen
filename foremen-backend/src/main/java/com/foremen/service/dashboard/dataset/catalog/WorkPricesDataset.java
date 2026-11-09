package com.foremen.service.dashboard.dataset.catalog;

import static com.foremen.service.dashboard.dataset.catalog.CatalogFields.*;

import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** FOR-10 dataset WORK_PRICES: company-wide net work prices. */
@Component
public class WorkPricesDataset implements Dataset {

    @Override
    public String code() {
        return "WORK_PRICES";
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("WORK_PRICES");
    }

    @Override
    public Class<?> rootEntity() {
        return WorkPriceEntity.class;
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("createdDate");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return concat(id(), createdDate(), str("workItem.code"), str("workItem.nameRU"),
                str("workItem.namePL"), str("currency.code"), money("netPrice", "currency.code"));
    }
}
