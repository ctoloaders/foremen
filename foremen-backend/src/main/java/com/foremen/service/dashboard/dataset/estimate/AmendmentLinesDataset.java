package com.foremen.service.dashboard.dataset.estimate;

import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.AmendmentStatus;
import com.foremen.dao.model.ChangeType;
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

/** Amendment lines. Free-text description is NOT exposed; lines of deleted amendments are excluded. */
@Component
public class AmendmentLinesDataset extends AbstractEstimateDataset {

    public AmendmentLinesDataset() {
        super("AMENDMENT_LINES", "AMENDMENTS", AmendmentLineEntity.class, "amendment.project.id",
                "amendment.effectiveDate", build());
    }

    @Override
    public Predicate extraPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return cb.notEqual(root.get("amendment").get("status"), AmendmentStatus.DELETED);
    }

    private static List<FieldDescriptor> build() {
        List<FieldDescriptor> f = new ArrayList<>();
        f.add(FieldDescriptor.of("id", FieldType.ID));
        f.addAll(projectFields("amendment.project"));
        f.add(FieldDescriptor.of("amendment.seqNo", FieldType.NUMBER));
        f.add(FieldDescriptor.of("amendment.effectiveDate", FieldType.DATE));
        f.add(enumField("changeType", "changeType", ChangeType.class));
        f.add(string("workItem.nameRU", "workItem.nameRU"));
        f.add(string("workItem.namePL", "workItem.namePL"));
        f.add(string("room.label", "room.label"));
        f.add(FieldDescriptor.of("deltaQuantity", FieldType.NUMBER));
        f.add(money("extraUnitPrice", "extraUnitPrice", null));
        f.add(FieldDescriptor.of("scheduleDeltaHalfDays", FieldType.NUMBER));
        return f;
    }
}
