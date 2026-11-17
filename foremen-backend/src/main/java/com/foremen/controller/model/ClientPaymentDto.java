package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.foremen.service.clientpayment.ClientPaymentKind;
import com.foremen.service.clientpayment.DisplayStatus;
import com.foremen.service.payment.ScheduleStatus;

/**
 * FOR-11-06 (R1.3, R1.4, R1.7): the staff read DTO of a unified client payment. A common block plus
 * exactly one per-kind block: {@link WorksBlock} for {@code WORKS}, {@link ProformaBlock} for
 * {@code MATERIALS}; the other is {@code null}.
 *
 * @param status               kind-independent display status (via {@code ClientPaymentStatusMapper})
 * @param storedStatus         the per-kind stored status name
 * @param effectiveBankAccount project account, else company account, else null (R4.4)
 */
public record ClientPaymentDto(
        Long id,
        Long projectId,
        ClientPaymentKind kind,
        DisplayStatus status,
        String storedStatus,
        boolean overdue,
        LocalDate dueDate,
        String currency,
        BigDecimal amountGross,
        BigDecimal paidGross,
        BigDecimal remainingGross,
        String note,
        String effectiveBankAccount,
        WorksBlock works,
        ProformaBlock proforma) implements ClientPaymentView {

    /** Works tranche detail. */
    public record WorksBlock(Long trancheId, Long scheduleId, Integer seqNo, ScheduleStatus scheduleStatus) {
    }

    /**
     * Materials proforma detail. {@code bankAccountSnapshot} is the account frozen at issue (R4.6),
     * null for a draft.
     */
    public record ProformaBlock(
            Long proformaId,
            String number,
            LocalDate issueDate,
            BigDecimal totalNet,
            BigDecimal totalVat,
            OffsetDateTime releasedAt,
            OffsetDateTime cancelledAt,
            String cancelReason,
            String pdfLanguage,
            boolean hasPdf,
            String bankAccountSnapshot,
            List<ClientPaymentLineSummaryDto> lines,
            List<PaymentSummary> payments) {
    }

    /**
     * FOR-11-03 (R23.3, R24.5): one bank-ledger payment applied to the proforma, STAFF detail only. Carries no
     * counterparty name, account or IBAN. Filled on the by-id read; empty on list reads.
     */
    public record PaymentSummary(Long linkId, LocalDate bookingDate, BigDecimal amount, String title) {
    }
}
