package com.foremen.service.dashboard.dataset.estimate;

import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.foremen.service.dashboard.dataset.estimate.EstimateDatasetSupport.*;

/** Estimate lines. Localized name fields stand in for the design's {@code workItem.name}/{@code unit.name}. */
@Component
public class EstimateLinesDataset extends AbstractEstimateDataset {

    private static final String CUR = "estimate.currency.code";

    public EstimateLinesDataset() {
        super("ESTIMATE_LINES", "ESTIMATE", EstimateLineEntity.class, "estimate.project.id",
                "createdDate", build());
    }

    private static List<FieldDescriptor> build() {
        List<FieldDescriptor> f = new ArrayList<>();
        f.add(FieldDescriptor.of("id", FieldType.ID));
        f.addAll(projectFields("estimate.project"));
        f.add(FieldDescriptor.of("lineNo", FieldType.NUMBER));
        f.add(string("workItem.code", "workItem.code"));
        f.add(string("workItem.nameRU", "workItem.nameRU"));
        f.add(string("workItem.namePL", "workItem.namePL"));
        f.add(string("unit.nameRU", "unit.nameRU"));
        f.add(string("unit.namePL", "unit.namePL"));
        f.add(FieldDescriptor.of("quantity", FieldType.NUMBER));
        f.add(money("unitPrice", "unitPrice", CUR));
        f.add(money("unitPriceOverride", "unitPriceOverride", CUR));
        f.add(money("valueNet", "valueNet", CUR));
        f.add(FieldDescriptor.of("createdDate", FieldType.DATETIME));
        return f;
    }
}
