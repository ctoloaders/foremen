package com.foremen.service.dashboard.dataset.execution;

import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.service.dashboard.query.QueryContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** {@code PURCHASE_LINES}: lines of project-scoped documents; no-project documents only for their uploader. */
@Component
public class PurchaseLinesDataset extends ExecutionDataset {

    public PurchaseLinesDataset() {
        super("PURCHASE_LINES", "PURCHASES", ProcurementLineEntity.class, "document.documentDate",
                ProcurementFields.lines());
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("document.project.id");
    }

    @Override
    public Predicate nullProjectPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return cb.and(cb.isNull(root.get("document").get("project")),
                cb.equal(root.get("document").get("uploadedBy").get("id"), ctx.userId()));
    }
}
