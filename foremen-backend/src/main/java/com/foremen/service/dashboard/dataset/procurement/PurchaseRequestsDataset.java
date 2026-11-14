package com.foremen.service.dashboard.dataset.procurement;

import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.dashboard.query.QueryContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * FOR-11-00 dataset {@code PURCHASE_REQUESTS} (Requirements 14.6, 14.7). Notes, reference link and
 * the overdue marker are not exposed. A BUYER sees the requests of every project.
 */
@Component
public class PurchaseRequestsDataset implements Dataset {

    public static final String CODE = "PURCHASE_REQUESTS";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("PURCHASE_REQUESTS");
    }

    @Override
    public Class<?> rootEntity() {
        return PurchaseRequestEntity.class;
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("project.id");
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("expectedDeliveryDate");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("project.id", FieldType.ID),
                FieldDescriptor.of("project.name", FieldType.STRING),
                FieldDescriptor.of("referenceNumber", FieldType.STRING),
                FieldDescriptor.of("deliveryCategory.nameRU", FieldType.STRING),
                FieldDescriptor.of("deliveryCategory.namePL", FieldType.STRING),
                FieldDescriptor.of("expectedDeliveryDate", FieldType.DATE),
                FieldDescriptor.of("assignedBuyer.id", FieldType.ID),
                FieldDescriptor.of("createdDate", FieldType.DATETIME));
    }

    /** BUYER sees every project: an always-true predicate ORed with the membership scope. */
    @Override
    public Predicate nullProjectPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return "BUYER".equals(ctx.roleCode()) ? cb.conjunction() : null;
    }
}
