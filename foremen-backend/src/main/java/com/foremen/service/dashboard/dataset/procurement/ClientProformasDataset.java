package com.foremen.service.dashboard.dataset.procurement;

import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.dashboard.query.QueryContext;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.proforma.ProformaStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * FOR-11-03 dataset {@code CLIENT_PROFORMAS} (Requirement 27.2). Totals and paid amount only: the
 * number, notes, cancel reason, PDF key and issuer are not exposed. BUYER and FINANCIER see every
 * project (Requirement 20.3).
 */
@Component
public class ClientProformasDataset implements Dataset {

    public static final String CODE = "CLIENT_PROFORMAS";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("CLIENT_PROFORMAS");
    }

    @Override
    public Class<?> rootEntity() {
        return ClientProformaEntity.class;
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("project.id");
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("issueDate");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("project.id", FieldType.ID),
                FieldDescriptor.of("project.name", FieldType.STRING),
                FieldDescriptor.of("status", FieldType.ENUM)
                        .withEnumValues(Arrays.stream(ProformaStatus.values()).map(Enum::name).toList()),
                FieldDescriptor.of("issueDate", FieldType.DATE),
                FieldDescriptor.of("dueDate", FieldType.DATE),
                FieldDescriptor.of("totalNet", FieldType.MONEY).withCurrencyPath("currency"),
                FieldDescriptor.of("totalVat", FieldType.MONEY).withCurrencyPath("currency"),
                FieldDescriptor.of("totalGross", FieldType.MONEY).withCurrencyPath("currency"),
                FieldDescriptor.of("paidGross", FieldType.MONEY).withCurrencyPath("currency"),
                FieldDescriptor.computed("overdue", FieldType.BOOLEAN, (cb, q, root) ->
                                cb.<Boolean>selectCase()
                                        .when(cb.and(
                                                cb.lessThan(root.<java.time.LocalDate>get("dueDate"), cb.localDate()),
                                                root.get("status").in(ProformaStatus.ISSUED,
                                                        ProformaStatus.PARTIALLY_PAID)), true)
                                        .otherwise(false))
                        .withFlags(true, true, true, false),
                FieldDescriptor.of("createdDate", FieldType.DATETIME));
    }

    /** BUYER and FINANCIER see every project: an always-true predicate ORed with the membership scope. */
    @Override
    public Predicate nullProjectPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return ctx.roleCode() != null
                && ProcurementProjectAccess.COMPANY_WIDE_ROLE_CODES.contains(ctx.roleCode())
                ? cb.conjunction() : null;
    }
}
