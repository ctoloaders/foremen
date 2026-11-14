package com.foremen.service.dashboard.dataset.procurement;

import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
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
 * FOR-11-00 dataset {@code PURCHASE_POSITIONS} (Requirements 14.6, 14.7, 24.1). No Money_Field, notes,
 * reference link, seller NIP or variants are exposed. A BUYER sees the positions of every project.
 */
@Component
public class PurchasePositionsDataset implements Dataset {

    public static final String CODE = "PURCHASE_POSITIONS";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("PURCHASE_POSITIONS");
    }

    @Override
    public Class<?> rootEntity() {
        return PurchasePositionEntity.class;
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("project.id");
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("plannedDeliveryDate");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("project.id", FieldType.ID),
                FieldDescriptor.of("project.name", FieldType.STRING),
                FieldDescriptor.of("status", FieldType.ENUM)
                        .withEnumValues(Arrays.stream(PositionStatus.values()).map(Enum::name).toList()),
                FieldDescriptor.of("sourcingPath", FieldType.ENUM)
                        .withEnumValues(Arrays.stream(SourcingPath.values()).map(Enum::name).toList()),
                FieldDescriptor.of("kind", FieldType.ENUM)
                        .withEnumValues(Arrays.stream(PositionKind.values()).map(Enum::name).toList()),
                FieldDescriptor.of("plannedMaterial.model", FieldType.STRING),
                FieldDescriptor.of("plannedMaterial.producer.nameRU", FieldType.STRING),
                FieldDescriptor.of("plannedMaterial.producer.namePL", FieldType.STRING),
                FieldDescriptor.of("quantity", FieldType.NUMBER),
                FieldDescriptor.of("unit.nameRU", FieldType.STRING),
                FieldDescriptor.of("unit.namePL", FieldType.STRING),
                FieldDescriptor.of("needByDate", FieldType.DATE),
                FieldDescriptor.of("orderByDate", FieldType.DATE),
                FieldDescriptor.of("decisionDeadline", FieldType.DATE),
                FieldDescriptor.of("plannedDeliveryDate", FieldType.DATE),
                FieldDescriptor.of("purchaseRequest.id", FieldType.ID),
                FieldDescriptor.of("createdDate", FieldType.DATETIME));
    }

    /** BUYER sees every project: an always-true predicate ORed with the membership scope. */
    @Override
    public Predicate nullProjectPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return "BUYER".equals(ctx.roleCode()) ? cb.conjunction() : null;
    }
}
