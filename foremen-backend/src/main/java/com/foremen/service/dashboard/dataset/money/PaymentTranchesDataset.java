package com.foremen.service.dashboard.dataset.money;

import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.payment.TrancheStatus;
import jakarta.persistence.criteria.Expression;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** {@code PAYMENT_TRANCHES}: schedule tranches with the computed outstanding amount. */
@Component
public class PaymentTranchesDataset extends MoneyDataset {

    public PaymentTranchesDataset() {
        // FOR-11-06 (R1.10): dueDate, amountGross, paidGross and status live on the unified base row
        // (client_payments) reached through "payment"; the config-facing field names are unchanged.
        super("PAYMENT_TRANCHES", "CLIENT_PAYMENTS", PaymentTrancheEntity.class, "dueDate", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("schedule.project.id", FieldType.ID),
                FieldDescriptor.of("schedule.project.name", FieldType.STRING),
                FieldDescriptor.of("seqNo", FieldType.NUMBER),
                FieldDescriptor.of("dueDate", "payment.dueDate", FieldType.DATE),
                money("amountGross", "payment.amountGross", "schedule.currency"),
                money("paidGross", "payment.paidGross", "schedule.currency"),
                FieldDescriptor.computed("outstanding", FieldType.MONEY, (cb, q, root) -> {
                    Expression<BigDecimal> amount = root.get("payment").<BigDecimal>get("amountGross");
                    Expression<BigDecimal> paid = root.get("payment").<BigDecimal>get("paidGross");
                    return cb.diff(amount, paid);
                }).withCurrencyPath("schedule.currency"),
                FieldDescriptor.of("status", "payment.status", FieldType.ENUM).withEnumValues(
                        Arrays.stream(TrancheStatus.values()).map(Enum::name).toList())));
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("schedule.project.id");
    }
}
