package com.foremen.service.bank.link;

import com.foremen.dao.model.LinkKind;

/**
 * FOR-07-04 (Requirement 17.4) — create / edit payload of a counterparty rule
 * ({@code POST/PUT /bank/counterparty-rules[/{id}]}). Only the targets of {@link #targetKind()} are
 * kept:
 * <ul>
 *   <li>{@code WORKER_PAYOUT} → {@link #workerId()} (required);</li>
 *   <li>{@code PROCUREMENT} → {@link #supplierName()} and / or {@link #supplierNip()} (one required);</li>
 *   <li>{@code CLIENT_PAYMENT} → {@link #paymentScheduleId()} (required; project = the schedule's);</li>
 *   <li>{@code OTHER_EXPENSE} → {@link #unplannedCategoryId()} (required) + optional {@link #projectId()};</li>
 *   <li>{@code OTHER_INCOME} → optional category + project;</li>
 *   <li>{@code INTERNAL_TRANSFER} → optional {@link #counterAccountId()}.</li>
 * </ul>
 *
 * @param counterpartyKey     {@code IBAN:{iban}} or {@code NAME:{normalized name}} (required on create;
 *                            ignored on edit — the key is the rule identity)
 * @param enabled             {@code null} → unchanged on edit, {@code true} on create
 */
public record CounterpartyRuleRequest(
        String counterpartyKey,
        String displayName,
        LinkKind targetKind,
        Long workerId,
        String supplierName,
        String supplierNip,
        Long projectId,
        Long paymentScheduleId,
        Long unplannedCategoryId,
        Long counterAccountId,
        Boolean enabled) {
}
