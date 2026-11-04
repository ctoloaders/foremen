package com.foremen.service.bank.link;

import java.time.OffsetDateTime;

import com.foremen.dao.model.BankCounterpartyRuleEntity;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.RuleOrigin;

/**
 * FOR-07-04 (Requirement 17.4) — read model of a counterparty rule ("Counterparty rules" tab).
 */
public record CounterpartyRuleDto(
        Long id,
        String counterpartyKey,
        String displayName,
        LinkKind targetKind,
        Long workerId,
        String workerName,
        String supplierName,
        String supplierNip,
        Long projectId,
        String projectName,
        Long paymentScheduleId,
        Long unplannedCategoryId,
        Long counterAccountId,
        String counterAccountName,
        boolean enabled,
        int hits,
        OffsetDateTime lastUsedAt,
        RuleOrigin origin) {

    public static CounterpartyRuleDto of(BankCounterpartyRuleEntity r) {
        return new CounterpartyRuleDto(
                r.getId(),
                r.getCounterpartyKey(),
                r.getDisplayName(),
                r.getTargetKind(),
                r.getWorker() == null ? null : r.getWorker().getId(),
                r.getWorker() == null ? null : r.getWorker().getName(),
                r.getSupplierName(),
                r.getSupplierNip(),
                r.getProject() == null ? null : r.getProject().getId(),
                r.getProject() == null ? null : r.getProject().getName(),
                r.getPaymentSchedule() == null ? null : r.getPaymentSchedule().getId(),
                r.getUnplannedCategory() == null ? null : r.getUnplannedCategory().getId(),
                r.getCounterAccount() == null ? null : r.getCounterAccount().getId(),
                r.getCounterAccount() == null ? null : r.getCounterAccount().getName(),
                r.isEnabled(),
                r.getHits(),
                r.getLastUsedAt(),
                r.getOrigin());
    }
}
