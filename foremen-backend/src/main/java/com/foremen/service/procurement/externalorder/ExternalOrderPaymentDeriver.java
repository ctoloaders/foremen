package com.foremen.service.procurement.externalorder;

import java.math.BigDecimal;
import java.util.List;

import com.foremen.dao.model.DocumentPaymentStatus;

/**
 * FOR-11-03 (R16.1, R16.2): an external order is ledger-paid when
 * {@code ledgerPaidGross >= payableGross} (payable &gt; 0) OR it has at least one linked document and every
 * linked document is {@link DocumentPaymentStatus#PAID}. An empty document list contributes nothing.
 */
public final class ExternalOrderPaymentDeriver {

    private ExternalOrderPaymentDeriver() {
    }

    public static boolean derive(BigDecimal payableGross, BigDecimal ledgerPaidGross,
                                 List<DocumentPaymentStatus> documentStatuses) {
        return paidByLedgerLinks(payableGross, ledgerPaidGross) || paidByDocuments(documentStatuses);
    }

    static boolean paidByLedgerLinks(BigDecimal payableGross, BigDecimal ledgerPaidGross) {
        if (payableGross == null || payableGross.signum() <= 0 || ledgerPaidGross == null) {
            return false;
        }
        return ledgerPaidGross.compareTo(payableGross) >= 0;
    }

    static boolean paidByDocuments(List<DocumentPaymentStatus> documentStatuses) {
        return documentStatuses != null && !documentStatuses.isEmpty()
                && documentStatuses.stream().allMatch(s -> s == DocumentPaymentStatus.PAID);
    }
}
