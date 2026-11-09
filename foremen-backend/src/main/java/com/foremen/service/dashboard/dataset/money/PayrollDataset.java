package com.foremen.service.dashboard.dataset.money;

import com.foremen.dao.model.WorkerPayrollLineEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** {@code PAYROLL}: monthly payroll lines; a WORKER only sees their own. No note is exposed. */
@Component
public class PayrollDataset extends MoneyDataset {

    public PayrollDataset() {
        super("PAYROLL", "PAYROLL", WorkerPayrollLineEntity.class, "month", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("worker.id", FieldType.ID),
                FieldDescriptor.of("worker.name", FieldType.STRING),
                FieldDescriptor.of("month", FieldType.DATE),
                FieldDescriptor.of("name", FieldType.STRING),
                money("amount", "amount", null)));
    }

    @Override
    public Optional<String> ownerPath() {
        return Optional.of("worker.id");
    }

    @Override
    public Set<String> ownerRestrictedRoles() {
        return Set.of("WORKER");
    }
}
