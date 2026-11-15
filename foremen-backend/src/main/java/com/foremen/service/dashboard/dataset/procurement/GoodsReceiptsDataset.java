package com.foremen.service.dashboard.dataset.procurement;

import com.foremen.dao.model.GoodsReceiptEntity;
import com.foremen.dao.model.ReceiptKind;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.dashboard.query.QueryContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * FOR-11-05 dataset {@code GOODS_RECEIPTS} (Requirements 14.2, 14.3). Exposes kind, quantity, event time and
 * the computed {@code voided} flag. Comment, void reason, client request id, storage fields and user ids are
 * deliberately not exposed. Widgets should filter {@code voided = false}. BUYER sees every project.
 */
@Component
public class GoodsReceiptsDataset implements Dataset {

    public static final String CODE = "GOODS_RECEIPTS";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("GOODS_RECEIPTS");
    }

    @Override
    public Class<?> rootEntity() {
        return GoodsReceiptEntity.class;
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("project.id");
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("occurredAt");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("project.id", FieldType.ID),
                FieldDescriptor.of("project.name", FieldType.STRING),
                FieldDescriptor.of("position.id", FieldType.ID),
                FieldDescriptor.of("kind", FieldType.ENUM)
                        .withEnumValues(Arrays.stream(ReceiptKind.values()).map(Enum::name).toList()),
                FieldDescriptor.of("quantity", FieldType.NUMBER),
                FieldDescriptor.of("occurredAt", FieldType.DATETIME).withFlags(true, true, true, false),
                FieldDescriptor.computed("voided", FieldType.BOOLEAN, (cb, q, root) ->
                                cb.<Boolean>selectCase()
                                        .when(cb.isNotNull(root.get("voidedAt")), true)
                                        .otherwise(false))
                        .withFlags(true, true, true, false));
    }

    /** BUYER reads every project: an always-true predicate ORed with the membership scope. */
    @Override
    public Predicate nullProjectPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return "BUYER".equals(ctx.roleCode()) ? cb.conjunction() : null;
    }
}
