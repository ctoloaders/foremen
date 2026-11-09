package com.foremen.service.dashboard.dataset.money;

import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import com.foremen.service.payment.ScheduleSource;
import com.foremen.service.payment.ScheduleStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code PAYMENT_SCHEDULES}: one payment schedule per project. */
@Component
public class PaymentSchedulesDataset extends MoneyDataset {

    public PaymentSchedulesDataset() {
        super("PAYMENT_SCHEDULES", "PAYMENTS", PaymentScheduleEntity.class, "createdDate", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("project.id", FieldType.ID),
                FieldDescriptor.of("project.name", FieldType.STRING),
                FieldDescriptor.of("currency", FieldType.STRING),
                money("basisGross", "basisGross", "currency"),
                money("unallocatedCredit", "unallocatedCredit", "currency"),
                enumField("source", ScheduleSource.class),
                enumField("status", ScheduleStatus.class),
                FieldDescriptor.of("createdDate", FieldType.DATETIME)));
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("project.id");
    }
}
