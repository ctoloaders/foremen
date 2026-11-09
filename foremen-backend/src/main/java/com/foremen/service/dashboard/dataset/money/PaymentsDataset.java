package com.foremen.service.dashboard.dataset.money;

import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PaymentTransactionEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.payment.TransactionStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@code PAYMENTS}: recorded incoming client payments, project-scoped through the schedule. Unmatched
 * payments (no schedule) are visible to ADMIN only. External reference and note are not exposed.
 */
@Component
public class PaymentsDataset extends MoneyDataset {

    public PaymentsDataset() {
        super("PAYMENTS", "PAYMENTS", PaymentTransactionEntity.class, "receivedAt", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("schedule.project.id", FieldType.ID),
                FieldDescriptor.of("schedule.project.name", FieldType.STRING),
                money("amountGross", "amountGross", "schedule.currency"),
                money("fee", "feeGross", "schedule.currency"),
                enumField("status", TransactionStatus.class),
                enumField("channel", PaymentChannel.class),
                FieldDescriptor.of("receivedAt", FieldType.DATETIME)));
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("schedule.project.id");
    }
}
