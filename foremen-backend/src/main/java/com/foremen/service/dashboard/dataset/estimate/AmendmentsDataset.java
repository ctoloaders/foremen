package com.foremen.service.dashboard.dataset.estimate;

import com.foremen.dao.model.AmendmentStatus;
import com.foremen.dao.model.EstimateAmendmentEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.dashboard.query.QueryContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.foremen.service.dashboard.dataset.estimate.EstimateDatasetSupport.*;

/** Estimate amendments. The free-text note is NOT exposed; soft-deleted amendments are excluded. */
@Component
public class AmendmentsDataset extends AbstractEstimateDataset {

    public AmendmentsDataset() {
        super("AMENDMENTS", "AMENDMENTS", EstimateAmendmentEntity.class, "project.id",
                "effectiveDate", build());
    }

    @Override
    public Predicate extraPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return cb.notEqual(root.get("status"), AmendmentStatus.DELETED);
    }

    private static List<FieldDescriptor> build() {
        List<FieldDescriptor> f = new ArrayList<>();
        f.add(FieldDescriptor.of("id", FieldType.ID));
        f.addAll(projectFields("project"));
        f.add(FieldDescriptor.of("seqNo", FieldType.NUMBER));
        f.add(enumField("status", "status", AmendmentStatus.class));
        f.add(FieldDescriptor.of("effectiveDate", FieldType.DATE));
        return f;
    }
}
