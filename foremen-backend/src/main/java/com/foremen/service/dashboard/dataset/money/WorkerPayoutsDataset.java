package com.foremen.service.dashboard.dataset.money;

import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** {@code WORKER_PAYOUTS}: payouts to workers; a WORKER only sees their own. No note is exposed. */
@Component
public class WorkerPayoutsDataset extends MoneyDataset {

    public WorkerPayoutsDataset() {
        super("WORKER_PAYOUTS", "PAYROLL", WorkerPayoutEntity.class, "payoutDate", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("worker.id", FieldType.ID),
                FieldDescriptor.of("worker.name", FieldType.STRING),
                FieldDescriptor.of("payoutDate", FieldType.DATE),
                FieldDescriptor.of("periodFrom", FieldType.DATE),
                FieldDescriptor.of("periodTo", FieldType.DATE),
                money("amount", "amount", null),
                money("suggestedAmount", "suggestedAmount", null),
                money("advanceApplied", "advanceApplied", null),
                enumField("paymentChannel", PaymentChannel.class),
                enumField("status", PayoutStatus.class),
                FieldDescriptor.of("isAdvance", FieldType.BOOLEAN),
                FieldDescriptor.of("paidAt", FieldType.DATETIME)));
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
