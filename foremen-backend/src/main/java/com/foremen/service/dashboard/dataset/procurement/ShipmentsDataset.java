package com.foremen.service.dashboard.dataset.procurement;

import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.dashboard.query.QueryContext;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.shipment.TrackingBackoff;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * FOR-11-04 dataset {@code SHIPMENTS} (Requirement 18.2). Exposes status, method, carrier code, dates and
 * the computed {@code degraded} flag only. Tracking number / URL, shop-order link, driver name and phone,
 * notes and raw tracking text are deliberately not exposed. BUYER and FINANCIER see every project
 * (Requirement 17.1).
 */
@Component
public class ShipmentsDataset implements Dataset {

    public static final String CODE = "SHIPMENTS";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("SHIPMENTS");
    }

    @Override
    public Class<?> rootEntity() {
        return ShipmentEntity.class;
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("project.id");
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("etaDate");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("project.id", FieldType.ID),
                FieldDescriptor.of("project.name", FieldType.STRING),
                FieldDescriptor.of("status", FieldType.ENUM)
                        .withEnumValues(Arrays.stream(ShipmentStatus.values()).map(Enum::name).toList()),
                FieldDescriptor.of("deliveryMethod", FieldType.ENUM)
                        .withEnumValues(Arrays.stream(DeliveryMethod.values()).map(Enum::name).toList()),
                FieldDescriptor.of("carrierCode", FieldType.STRING),
                FieldDescriptor.of("etaDate", FieldType.DATE).withFlags(true, true, true, false),
                FieldDescriptor.of("shippedAt", FieldType.DATETIME).withFlags(true, true, true, false),
                FieldDescriptor.of("deliveredAt", FieldType.DATETIME).withFlags(true, true, true, false),
                FieldDescriptor.computed("degraded", FieldType.BOOLEAN, (cb, q, root) ->
                                cb.<Boolean>selectCase()
                                        .when(cb.greaterThanOrEqualTo(root.<Integer>get("consecutiveFailures"),
                                                TrackingBackoff.DEGRADED_THRESHOLD), true)
                                        .otherwise(false))
                        .withFlags(true, true, true, false));
    }

    /** BUYER and FINANCIER see every project: an always-true predicate ORed with the membership scope. */
    @Override
    public Predicate nullProjectPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return ctx.roleCode() != null
                && ProcurementProjectAccess.COMPANY_WIDE_ROLE_CODES.contains(ctx.roleCode())
                ? cb.conjunction() : null;
    }
}
