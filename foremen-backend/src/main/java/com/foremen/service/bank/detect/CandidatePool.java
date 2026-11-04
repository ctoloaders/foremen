package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.foremen.service.bank.detect.MatchCandidate.TargetRef;
import com.foremen.service.bank.detect.MatchCandidate.TargetType;
import com.foremen.service.bank.detect.MatchInputs.CounterpartyRule;
import com.foremen.service.bank.detect.MatchInputs.DocumentCandidate;
import com.foremen.service.bank.detect.MatchInputs.OwnAccount;
import com.foremen.service.bank.detect.MatchInputs.PayoutCandidate;
import com.foremen.service.bank.detect.MatchInputs.ScheduleCandidate;

/**
 * FOR-07-04 (design "Candidate providers") — the candidates loaded once per detection batch by
 * {@link DetectionCandidateProviders}, narrowed per line by {@link #inputsFor}:
 * <ul>
 *   <li>own accounts — every line;</li>
 *   <li>client schedules — {@code INCOME} lines only;</li>
 *   <li>payouts — {@code EXPENSE} lines, payout date within ±{@value #PAYOUT_WINDOW_DAYS} days of the
 *       booking date;</li>
 *   <li>procurement documents — {@code EXPENSE} lines, invoice or upload date within
 *       −{@value #DOC_DAYS_BEFORE}..+{@value #DOC_DAYS_AFTER} days of the booking date.</li>
 * </ul>
 * Targets consumed by an auto-link earlier in the same batch are excluded so two lines of one sync never
 * auto-link the same payout / document. Pure (no Spring, no JPA).
 *
 * @param ownAccounts the company's own BANK accounts with an IBAN
 * @param schedules   published client schedules (one entry per CLIENT member)
 * @param payouts     BANK_TRANSFER payouts in the batch window
 * @param documents   procurement documents in the batch window
 * @param labels      human labels per target ({@link #labelKey}) for the AI candidate list
 */
public record CandidatePool(
        List<OwnAccount> ownAccounts,
        List<ScheduleCandidate> schedules,
        List<PayoutCandidate> payouts,
        List<DocumentCandidate> documents,
        Map<String, Label> labels) {

    /** Payout date window around the booking date (± days). */
    public static final int PAYOUT_WINDOW_DAYS = 30;
    /** Document date window relative to the booking date: [−90, +30] days. */
    public static final int DOC_DAYS_BEFORE = 90;
    public static final int DOC_DAYS_AFTER = 30;

    /** A human label for an AI candidate. */
    public record Label(String label, BigDecimal amount, LocalDate date) {
    }

    public CandidatePool {
        ownAccounts = ownAccounts == null ? List.of() : List.copyOf(ownAccounts);
        schedules = schedules == null ? List.of() : List.copyOf(schedules);
        payouts = payouts == null ? List.of() : List.copyOf(payouts);
        documents = documents == null ? List.of() : List.copyOf(documents);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    /** An empty pool. */
    public static CandidatePool empty() {
        return new CandidatePool(null, null, null, null, null);
    }

    /** The label key of a target (the project part of a ref is ignored). */
    public static String labelKey(TargetType type, long id) {
        return type + ":" + id;
    }

    /** The consumption key of a target ({@link #inputsFor} {@code consumed} set). */
    public static String consumedKey(TargetRef ref) {
        return labelKey(ref.type(), ref.id());
    }

    /**
     * The matcher inputs for one line.
     *
     * @param txn      the line facts
     * @param rules    the enabled counterparty rules for the line's key
     * @param consumed {@link #consumedKey} of targets auto-linked earlier in the batch
     */
    public MatchInputs inputsFor(TxnFacts txn, List<CounterpartyRule> rules, Set<String> consumed) {
        Objects.requireNonNull(txn, "txn");
        Set<String> used = consumed == null ? Set.of() : consumed;
        LocalDate booking = txn.bookingDate();
        if (txn.direction() == TxnFacts.Direction.INCOME) {
            return new MatchInputs(ownAccounts, rules, schedules, List.of(), List.of());
        }
        List<PayoutCandidate> p = payouts.stream()
                .filter(c -> c.payoutDate() != null
                        && Math.abs(c.payoutDate().toEpochDay() - booking.toEpochDay()) <= PAYOUT_WINDOW_DAYS)
                .filter(c -> !used.contains(labelKey(TargetType.WORKER_PAYOUT, c.payoutId())))
                .toList();
        List<DocumentCandidate> d = documents.stream()
                .filter(c -> inDocWindow(c.invoiceDate(), booking) || inDocWindow(c.uploadDate(), booking))
                .filter(c -> !used.contains(labelKey(TargetType.PROCUREMENT_DOCUMENT, c.documentId())))
                .toList();
        return new MatchInputs(ownAccounts, rules, List.of(), p, d);
    }

    /** The AI candidate list (R15.2) for ranked matcher candidates: opaque ref + human label. */
    public List<ClassificationInput.Candidate> aiCandidates(List<MatchCandidate> ranked) {
        if (ranked == null) {
            return List.of();
        }
        return ranked.stream().map(c -> {
            TargetRef ref = c.targetRef();
            Label l = labels.get(labelKey(ref.type(), ref.id()));
            String label = l == null || l.label() == null ? c.kind() + " " + ref.asString() : l.label();
            return new ClassificationInput.Candidate(ref.asString(), c.kind().name(), label,
                    l == null ? null : l.amount(), l == null ? null : l.date());
        }).toList();
    }

    static boolean inDocWindow(LocalDate docDate, LocalDate booking) {
        return docDate != null
                && !docDate.isBefore(booking.minusDays(DOC_DAYS_BEFORE))
                && !docDate.isAfter(booking.plusDays(DOC_DAYS_AFTER));
    }
}
