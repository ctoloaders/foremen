package com.foremen.service.bank.detect;

import java.util.Collection;

import com.foremen.dao.model.BankTransactionSuggestionEntity;
import com.foremen.dao.model.LinkKind;

/**
 * FOR-11-03 (R10.6, R19) - published when a bank transaction received a suggestion for a client proforma or
 * an external order. Consumed (task 6.1) by the financier notification
 * {@code notification.invoice.matchProposed}. Published once per transaction per detection run.
 *
 * @param transactionId the bank transaction that received the proposal
 */
public record LedgerMatchProposedEvent(Long transactionId) {

    /** True when the kind is one of the two FOR-11-03 kinds. */
    public static boolean concerns(LinkKind kind) {
        return kind == LinkKind.PROFORMA_PAYMENT || kind == LinkKind.EXTERNAL_ORDER_PAYMENT;
    }

    /** True when at least one stored suggestion targets a proforma or an external order. */
    public static boolean concerns(Collection<BankTransactionSuggestionEntity> suggestions) {
        return suggestions != null && suggestions.stream().anyMatch(s -> concerns(s.getKind()));
    }
}
