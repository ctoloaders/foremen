package com.foremen.service.bank.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.SystemSource;
import com.foremen.service.bank.ledger.LedgerFilter.LedgerDirection;

/**
 * FOR-07-04 (Requirements 20.2–20.5, 21.6; design §REST API) — read DTOs of the ledger
 * ({@code /api/bank/transactions}). They never carry credentials or provider secrets; the provider
 * payload is only filled for ADMIN ({@link LedgerDetail#providerPayload()}).
 */
public final class LedgerDtos {

    private LedgerDtos() {
    }

    /** {@code GET /bank/transactions} — {@code {content[], page, totals}}. */
    public record LedgerPage(List<LedgerRow> content, PageInfo page, LedgerTotals totals) {
    }

    /** Page metadata (0-based {@code number}). */
    public record PageInfo(int number, int size, long totalElements, int totalPages) {
    }

    /**
     * Totals for the whole filter (not just the page), R20.4. Amounts are per currency in
     * {@link #byCurrency}; the top-level {@code income} / {@code expense} / {@code net} are set only when
     * the filter spans at most one currency (zero when empty), otherwise {@code null}. {@code IGNORED}
     * lines are excluded from the amounts (R17.5) but counted in {@link #count}.
     *
     * @param income   Σ positive amounts
     * @param expense  Σ |negative amounts| (positive number)
     * @param net      income − expense
     * @param count    number of matching lines
     * @param toReview matching lines in {@code To_Review}
     */
    public record LedgerTotals(BigDecimal income, BigDecimal expense, BigDecimal net, long count, long toReview,
                               List<CurrencyTotals> byCurrency) {
    }

    /** Income / expense / net of one currency. */
    public record CurrencyTotals(String currency, BigDecimal income, BigDecimal expense, BigDecimal net) {
    }

    /**
     * A ledger row (R20.2).
     *
     * @param locked          at least one link is locked (reconciled client payment)
     * @param editable        a {@code MANUAL} cash entry without locked links (edit / delete allowed)
     * @param detection       the auto-detection marker, {@code null} for none
     * @param openSuggestions number of {@code OPEN} suggestions
     */
    @SuppressWarnings("java:S107")
    public record LedgerRow(
            Long id,
            LedgerChannel channel,
            LedgerOrigin origin,
            BankTxnStatus status,
            LocalDate bookingDate,
            LocalDate operationDate,
            LedgerDirection direction,
            BigDecimal amount,
            String currency,
            String bankName,
            String bankLogoUrl,
            Long connectionId,
            Long accountId,
            String accountName,
            String accountIbanLast4,
            String counterpartyName,
            String counterpartyIban,
            String title,
            LinkStatus linkStatus,
            boolean locked,
            boolean editable,
            Detection detection,
            List<LinkView> links,
            int openSuggestions) {
    }

    /** Auto-detection marker: the source of the line's RULE / AI link or of its top open suggestion. */
    public record Detection(DetectionSource source, BigDecimal confidence, String rationale) {
    }

    /** An applied link with its target label / URL (R20.2 "linked entity", R20.5). */
    @SuppressWarnings("java:S107")
    public record LinkView(
            Long id,
            LinkKind kind,
            BigDecimal amount,
            String label,
            Long projectId,
            String projectName,
            String targetUrl,
            DetectionSource source,
            BigDecimal confidence,
            boolean confirmed,
            OffsetDateTime confirmedAt,
            String confirmedBy,
            boolean locked,
            String note,
            Long workerPayoutId,
            Long procurementDocumentId,
            DocumentPaymentStatus documentPaymentStatus,
            Long paymentScheduleId,
            Long paymentTransactionId,
            Long unplannedCategoryId,
            Long counterAccountId) {
    }

    /** An {@code OPEN} suggestion of a line (drawer "Confirm" / "Reject"). */
    @SuppressWarnings("java:S107")
    public record SuggestionView(
            Long id,
            LinkKind kind,
            BigDecimal amount,
            int rank,
            String rationale,
            DetectionSource source,
            BigDecimal confidence,
            String label,
            Long projectId,
            String projectName,
            String targetUrl,
            Long workerPayoutId,
            Long procurementDocumentId,
            Long paymentScheduleId,
            Long unplannedCategoryId,
            Long counterAccountId) {
    }

    /**
     * {@code GET /bank/transactions/{id}} — the row plus every line field and the open suggestions.
     * {@code providerPayload} / {@code counterpartyRaw} are {@code null} unless the caller is ADMIN (R21.6).
     */
    @SuppressWarnings("java:S107")
    public record LedgerDetail(
            LedgerRow line,
            String externalId,
            SystemSource systemSource,
            Long systemSourceId,
            String counterpartyKey,
            String providerCategory,
            String ignoredReason,
            String ignoredBy,
            OffsetDateTime ignoredAt,
            OffsetDateTime reviewedAt,
            String reviewedBy,
            OffsetDateTime aiClassifiedAt,
            OffsetDateTime ingestedAt,
            String createdByUser,
            BigDecimal linkedAmount,
            BigDecimal remainingAmount,
            List<SuggestionView> suggestions,
            Map<String, Object> counterpartyRaw,
            Map<String, Object> providerPayload) {
    }

    /** The type of a link-target picker item. */
    public enum TargetType {
        PAYOUT,
        WORKER,
        DOCUMENT,
        SCHEDULE,
        CATEGORY,
        PROJECT,
        ACCOUNT
    }

    /**
     * One picker item of {@code GET /bank/transactions/{id}/link-targets} (R17.2). Only the fields that
     * apply to the {@link #targetType} are set.
     *
     * @param amount        payout amount / document total / schedule open remainder / account balance
     * @param remaining     what is still linkable on the target (payout / document), else {@code null}
     * @param status        payout status / schedule status / project status / account type
     * @param paymentStatus procurement document payment status
     * @param score         matcher score against the line (documents), else {@code null}
     */
    @SuppressWarnings("java:S107")
    public record LinkTarget(
            TargetType targetType,
            Long id,
            String label,
            String sublabel,
            BigDecimal amount,
            BigDecimal remaining,
            String currency,
            LocalDate date,
            String status,
            DocumentPaymentStatus paymentStatus,
            Long projectId,
            String projectName,
            Long workerId,
            BigDecimal score) {
    }

    /**
     * One ranked line of {@code GET /bank/transactions/link-candidates?procurementDocumentId=} (R28.1).
     *
     * @param remaining the line's unlinked amount
     * @param score     procurement score of the document against the line (0 when no signal)
     * @param reasons   the matcher reasons
     */
    public record LineCandidate(LedgerRow line, BigDecimal remaining, BigDecimal score, List<String> reasons) {
    }
}
