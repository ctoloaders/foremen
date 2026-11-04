package com.foremen.service.bank.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LinkKind;

/**
 * FOR-07-04 (Requirement 20.3; design §REST API "Ledger filter params") — the ledger list filter. Every
 * field is optional; {@code null} / empty means "no restriction". All set fields are AND-combined; the
 * multi-valued fields ({@link #linkStatuses}, {@link #detections}) are OR-combined within themselves.
 *
 * @param from           effective date ≥ from (booking date, operation date while {@code PENDING})
 * @param to             effective date ≤ to
 * @param channel        {@code BANK} / {@code CASH}
 * @param direction      income ({@code amount > 0}) / expense ({@code amount < 0})
 * @param connectionId   lines of the accounts of one bank connection
 * @param accountId      lines of one account (bank or cash)
 * @param linkStatuses   link statuses incl. the {@link LedgerStatusFilter#TO_REVIEW} group
 * @param kind           lines with at least one link of this kind
 * @param projectId      lines with at least one link to this project
 * @param detections     {@code RULE} / {@code AI} / {@code MANUAL} / {@code NONE}
 * @param amountMin      |amount| ≥ amountMin
 * @param amountMax      |amount| ≤ amountMax
 * @param q              free text over title, counterparty name and counterparty IBAN
 * @param includePending {@code false} → {@code POSTED} lines only ({@code null} = {@code true})
 * @param transactionId  exactly this line (deep links {@code ?transactionId=})
 */
public record LedgerFilter(
        LocalDate from,
        LocalDate to,
        LedgerChannel channel,
        LedgerDirection direction,
        Long connectionId,
        Long accountId,
        List<LedgerStatusFilter> linkStatuses,
        LinkKind kind,
        Long projectId,
        List<DetectionFilter> detections,
        BigDecimal amountMin,
        BigDecimal amountMax,
        String q,
        Boolean includePending,
        Long transactionId) {

    public LedgerFilter {
        linkStatuses = linkStatuses == null ? List.of() : List.copyOf(linkStatuses);
        detections = detections == null ? List.of() : List.copyOf(detections);
    }

    /** No restriction at all. */
    public static LedgerFilter empty() {
        return new LedgerFilter(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    /** The same filter restricted to the given link statuses (re-run detection on a selection). */
    public LedgerFilter withLinkStatuses(List<LedgerStatusFilter> statuses) {
        return new LedgerFilter(from, to, channel, direction, connectionId, accountId, statuses, kind, projectId,
                detections, amountMin, amountMax, q, includePending, transactionId);
    }

    /** Ledger line direction (sign of the amount). */
    public enum LedgerDirection {
        INCOME,
        EXPENSE
    }

    /**
     * A link-status filter value: every {@link com.foremen.dao.model.LinkStatus} plus the {@code TO_REVIEW}
     * group ({@code UNLINKED ∪ SUGGESTED ∪ PARTIALLY_LINKED ∪ AUTO_LINKED not yet reviewed}).
     */
    public enum LedgerStatusFilter {
        UNLINKED,
        SUGGESTED,
        AUTO_LINKED,
        PARTIALLY_LINKED,
        LINKED,
        IGNORED,
        TO_REVIEW
    }

    /**
     * Auto-detection filter (R20.3): {@code RULE} / {@code AI} — a link or an {@code OPEN} suggestion of
     * that source; {@code MANUAL} — a {@code MANUAL} link; {@code NONE} — neither links nor open
     * suggestions.
     */
    public enum DetectionFilter {
        RULE,
        AI,
        MANUAL,
        NONE
    }
}
