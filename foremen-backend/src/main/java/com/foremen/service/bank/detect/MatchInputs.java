package com.foremen.service.bank.detect;

import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.LinkKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * FOR-07-04 (design "Candidate providers") — the plain candidate lists loaded by the candidate
 * providers and fed to the pure {@link RuleMatcher}. The providers may pre-filter, but the matcher
 * re-applies every eligibility rule itself (payout channel, document status, direction).
 *
 * @param ownAccounts the company's own {@code BANK} accounts (internal-transfer detection)
 * @param rules       enabled / disabled counterparty rules for the line's counterparty key
 * @param schedules   client payment schedules (INCOME)
 * @param payouts     worker payouts (EXPENSE)
 * @param documents   procurement documents (EXPENSE)
 */
public record MatchInputs(
        List<OwnAccount> ownAccounts,
        List<CounterpartyRule> rules,
        List<ScheduleCandidate> schedules,
        List<PayoutCandidate> payouts,
        List<DocumentCandidate> documents) {

    public MatchInputs {
        ownAccounts = ownAccounts == null ? List.of() : List.copyOf(ownAccounts);
        rules = rules == null ? List.of() : List.copyOf(rules);
        schedules = schedules == null ? List.of() : List.copyOf(schedules);
        payouts = payouts == null ? List.of() : List.copyOf(payouts);
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    /** No candidates at all. */
    public static MatchInputs empty() {
        return new MatchInputs(null, null, null, null, null);
    }

    /** An own bank account. */
    public record OwnAccount(long accountId, String iban) {
    }

    /**
     * A counterparty rule (Requirement 14.3 / 17.3). Only {@code enabled} rules whose
     * {@code counterpartyKey} equals the line's key are applied.
     */
    public record CounterpartyRule(
            String counterpartyKey,
            LinkKind targetKind,
            Long workerId,
            String supplierName,
            String supplierNip,
            Long projectId,
            Long paymentScheduleId,
            Long unplannedCategoryId,
            Long counterAccountId,
            boolean enabled) {

        public CounterpartyRule {
            Objects.requireNonNull(targetKind, "targetKind");
        }
    }

    /**
     * A client payment schedule (Requirement 14.4).
     *
     * @param openTrancheRemainders open (unpaid) remainders of the schedule's tranches
     */
    public record ScheduleCandidate(
            long scheduleId,
            long projectId,
            boolean published,
            String clientName,
            String clientNip,
            List<BigDecimal> openTrancheRemainders) {

        public ScheduleCandidate {
            openTrancheRemainders = openTrancheRemainders == null ? List.of()
                    : List.copyOf(openTrancheRemainders);
        }
    }

    /**
     * A worker payout (Requirement 14.5).
     *
     * @param fullyLinked {@code true} when the payout's amount is already covered by ledger links
     */
    public record PayoutCandidate(
            long payoutId,
            long workerId,
            String workerName,
            BigDecimal amount,
            LocalDate payoutDate,
            PayoutStatus status,
            PaymentChannel paymentChannel,
            boolean fullyLinked) {
    }

    /** A procurement document (Requirement 14.6, 28.2). */
    public record DocumentCandidate(
            long documentId,
            BigDecimal documentTotal,
            String invoiceNumber,
            String supplierName,
            String supplierNip,
            LocalDate invoiceDate,
            LocalDate uploadDate,
            DocumentPaymentStatus paymentStatus) {
    }
}
