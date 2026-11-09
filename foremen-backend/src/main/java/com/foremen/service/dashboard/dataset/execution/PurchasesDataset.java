package com.foremen.service.dashboard.dataset.execution;

import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.service.dashboard.query.QueryContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** {@code PURCHASES}: project-scoped procurement documents; no-project rows only for their uploader. */
@Component
public class PurchasesDataset extends ExecutionDataset {

    public PurchasesDataset() {
        super("PURCHASES", "PURCHASES", ProcurementDocumentEntity.class, "documentDate",
                ProcurementFields.documents());
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("project.id");
    }

    @Override
    public Predicate nullProjectPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return cb.and(cb.isNull(root.get("project")),
                cb.equal(root.get("uploadedBy").get("id"), ctx.userId()));
    }
}
