package com.foremen.service.dashboard.dataset.money;

import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.payment.TrancheStatus;
import jakarta.persistence.criteria.Expression;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** {@code PAYMENT_TRANCHES}: schedule tranches with the computed outstanding amount. */
@Component
public class PaymentTranchesDataset extends MoneyDataset {

    public PaymentTranchesDataset() {
        super("PAYMENT_TRANCHES", "PAYMENTS", PaymentTrancheEntity.class, "dueDate", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("schedule.project.id", FieldType.ID),
                FieldDescriptor.of("schedule.project.name", FieldType.STRING),
                FieldDescriptor.of("seqNo", FieldType.NUMBER),
                FieldDescriptor.of("dueDate", FieldType.DATE),
                money("amountGross", "amountGross", "schedule.currency"),
                money("paidGross", "paidGross", "schedule.currency"),
                FieldDescriptor.computed("outstanding", FieldType.MONEY, (cb, q, root) -> {
                    Expression<BigDecimal> amount = root.get("amountGross");
                    Expression<BigDecimal> paid = root.get("paidGross");
                    return cb.diff(amount, paid);
                }).withCurrencyPath("schedule.currency"),
                enumField("status", TrancheStatus.class)));
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("schedule.project.id");
    }
}
