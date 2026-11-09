package com.foremen.service.dashboard.dataset.estimate;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.foremen.service.dashboard.dataset.estimate.EstimateDatasetSupport.*;

/** Project estimates. */
@Component
public class EstimatesDataset extends AbstractEstimateDataset {

    public EstimatesDataset() {
        super("ESTIMATES", "ESTIMATE", EstimateEntity.class, "project.id", "createdDate", build());
    }

    private static List<FieldDescriptor> build() {
        List<FieldDescriptor> f = new ArrayList<>();
        f.add(FieldDescriptor.of("id", FieldType.ID));
        f.addAll(projectFields("project"));
        f.add(enumField("status", "status", EstimateStatus.class));
        f.add(string("currency.code", "currency.code"));
        f.add(money("totalNet", "totalNet", "currency.code"));
        f.add(money("totalVat", "totalVat", "currency.code"));
        f.add(money("totalGross", "totalGross", "currency.code"));
        f.add(string("appliedPackageCode", "appliedPackageCode"));
        f.add(FieldDescriptor.of("createdDate", FieldType.DATETIME));
        return f;
    }
}
