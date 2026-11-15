package com.foremen.service.dashboard.dataset.procurement;

import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.dashboard.query.QueryContext;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.externalorder.ExternalOrderStatus;
import com.foremen.service.procurement.externalorder.PaidSource;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * FOR-11-03 dataset {@code EXTERNAL_ORDERS} (Requirement 27.2). Only order-level totals are exposed:
 * the payable gross is an aggregate; purchase-line prices, document ids/keys, notes, reasons and links
 * are not. BUYER and FINANCIER see every project (Requirement 20.3).
 */
@Component
public class ExternalOrdersDataset implements Dataset {

    public static final String CODE = "EXTERNAL_ORDERS";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> requiredResources() {
        return Set.of("EXTERNAL_ORDERS");
    }

    @Override
    public Class<?> rootEntity() {
        return ExternalOrderEntity.class;
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("project.id");
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.of("createdDate");
    }

    @Override
    public List<FieldDescriptor> fields() {
        return List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("project.id", FieldType.ID),
                FieldDescriptor.of("project.name", FieldType.STRING),
                FieldDescriptor.of("seller.id", FieldType.ID),
                FieldDescriptor.of("seller.nameRU", FieldType.STRING),
                FieldDescriptor.of("seller.namePL", FieldType.STRING),
                FieldDescriptor.of("status", FieldType.ENUM)
                        .withEnumValues(Arrays.stream(ExternalOrderStatus.values()).map(Enum::name).toList()),
                FieldDescriptor.of("paidSource", FieldType.ENUM)
                        .withEnumValues(Arrays.stream(PaidSource.values()).map(Enum::name).toList()),
                FieldDescriptor.of("deliveryCostNet", FieldType.MONEY),
                FieldDescriptor.computed("payableGross", FieldType.MONEY, ExternalOrdersDataset::payableGross),
                FieldDescriptor.of("assignedBuyer.id", FieldType.ID),
                FieldDescriptor.of("sentAt", FieldType.DATETIME),
                FieldDescriptor.of("paidAt", FieldType.DATETIME),
                FieldDescriptor.of("expectedShipDate", FieldType.DATE),
                FieldDescriptor.of("expectedDeliveryDate", FieldType.DATE),
                FieldDescriptor.of("createdDate", FieldType.DATETIME));
    }

    /** BUYER and FINANCIER see every project: an always-true predicate ORed with the membership scope. */
    @Override
    public Predicate nullProjectPredicate(QueryContext ctx, CriteriaBuilder cb, Root<?> root) {
        return ctx.roleCode() != null
                && ProcurementProjectAccess.COMPANY_WIDE_ROLE_CODES.contains(ctx.roleCode())
                ? cb.conjunction() : null;
    }

    /**
     * Sum of the per-line rounded gross amounts plus the rounded delivery gross, mirroring
     * {@code ExternalOrderMath} (HALF_UP at scale 2 per line; unknown VAT means gross = net).
     */
    private static Expression<BigDecimal> payableGross(CriteriaBuilder cb, CriteriaQuery<?> q, Root<?> root) {
        Subquery<BigDecimal> lines = q.subquery(BigDecimal.class);
        Root<ExternalOrderLineEntity> l = lines.from(ExternalOrderLineEntity.class);
        Expression<BigDecimal> rate = l.join("purchaseVatRate", JoinType.LEFT).<BigDecimal>get("rate");
        Expression<BigDecimal> net = round2(cb, cb.prod(l.<BigDecimal>get("quantity"), l.<BigDecimal>get("unitPurchaseNet")));
        lines.select(cb.coalesce(cb.sum(grossOf(cb, net, rate)), BigDecimal.ZERO))
                .where(cb.equal(l.get("externalOrder"), root));

        Subquery<BigDecimal> deliveryRate = q.subquery(BigDecimal.class);
        Root<VatRateEntity> v = deliveryRate.from(VatRateEntity.class);
        deliveryRate.select(v.<BigDecimal>get("rate"))
                .where(cb.equal(v.get("id"), root.get("deliveryVatRate").get("id")));
        Expression<BigDecimal> delivery = grossOf(cb, root.<BigDecimal>get("deliveryCostNet"), deliveryRate);

        return cb.sum(lines, delivery);
    }

    /** {@code round(net * (1 + coalesce(rate, 0) / 100), 2)}. */
    private static Expression<BigDecimal> grossOf(CriteriaBuilder cb, Expression<BigDecimal> net,
                                                  Expression<BigDecimal> rate) {
        Expression<BigDecimal> hundredPlusRate = cb.sum(cb.coalesce(rate, BigDecimal.ZERO), BigDecimal.valueOf(100));
        @SuppressWarnings("unchecked")
        Expression<BigDecimal> scaled = (Expression<BigDecimal>) (Expression<?>)
                cb.quot(cb.prod(net, hundredPlusRate), BigDecimal.valueOf(100));
        return round2(cb, scaled);
    }

    private static Expression<BigDecimal> round2(CriteriaBuilder cb, Expression<BigDecimal> value) {
        return cb.function("round", BigDecimal.class, value, cb.literal(2));
    }
}
