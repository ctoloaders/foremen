package com.foremen.service.bank.detect;

import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.LinkKind;
import com.foremen.service.bank.detect.MatchCandidate.TargetRef;
import com.foremen.service.bank.detect.MatchCandidate.TargetType;
import com.foremen.service.bank.detect.MatchInputs.CounterpartyRule;
import com.foremen.service.bank.detect.MatchInputs.DocumentCandidate;
import com.foremen.service.bank.detect.MatchInputs.OwnAccount;
import com.foremen.service.bank.detect.MatchInputs.PayoutCandidate;
import com.foremen.service.bank.detect.MatchInputs.ScheduleCandidate;
import com.foremen.service.bank.ingest.IbanNormalizer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FOR-07-04 (Requirements 14.2–14.8, 28.2) — the pure, deterministic rule matcher.
 *
 * <p>Scores the {@link MatchInputs} candidates against one ledger line ({@link TxnFacts}) using the
 * design scoring table (all scores capped at 1.00) and applies the decision rule:
 * <ul>
 *   <li>{@link Decision#AUTO_APPLY} — top ≥ auto-threshold and (top − runner-up) ≥ margin;</li>
 *   <li>{@link Decision#SUGGEST} — candidates ≥ suggest-threshold, at most {@code maxSuggestions};</li>
 *   <li>{@link Decision#NONE} — otherwise.</li>
 * </ul>
 *
 * <p>Eligibility is enforced here regardless of what the providers loaded: payouts only with
 * {@code paymentChannel = BANK_TRANSFER} ({@code PLANNED}, or {@code PAID} not fully linked);
 * documents only in {@code UNMATCHED} / {@code PARTIALLY_PAID}; only published schedules; every
 * emitted kind is allowed for the line direction ({@link ClassifierAnswerValidator#allowedKinds}).
 *
 * <p>Scores are exact decimals (scale 2) so threshold and margin comparisons are free of
 * floating-point drift; ranking ties are broken by {@link MatchCandidate#RANKING}, so the output does
 * not depend on the order of the input lists. No Spring, no JPA.
 */
public final class RuleMatcher {

    // Reason codes (stable, machine-readable; joined into the suggestion rationale).
    public static final String R_OWN_ACCOUNT = "OWN_ACCOUNT_IBAN";
    public static final String R_PROJECT_REFERENCE = "PROJECT_REFERENCE";
    public static final String R_CLIENT_MATCH = "CLIENT_MATCH";
    public static final String R_TRANCHE_AMOUNT = "TRANCHE_AMOUNT";
    public static final String R_RULE_HIT = "COUNTERPARTY_RULE";
    public static final String R_AMOUNT = "AMOUNT_EXACT";
    public static final String R_DATE = "DATE_WINDOW";
    public static final String R_NAME = "NAME_MATCH";
    public static final String R_ALREADY_PAID = "PAYOUT_ALREADY_PAID";
    public static final String R_INVOICE_NUMBER = "INVOICE_NUMBER";
    public static final String R_SUPPLIER = "SUPPLIER_MATCH";

    // Score table in hundredths.
    static final int INTERNAL_TRANSFER = 100;
    static final int PROJECT_REFERENCE = 95;
    static final int CLIENT_NAME = 40;
    static final int CLIENT_TRANCHE = 40;
    static final int CLIENT_RULE = 30;
    static final int PAYOUT_AMOUNT = 50;
    static final int PAYOUT_DATE = 20;
    static final int PAYOUT_NAME = 20;
    static final int PAYOUT_RULE = 30;
    static final int PAYOUT_PAID_PENALTY = -10;
    static final int DOC_AMOUNT = 50;
    static final int DOC_INVOICE = 40;
    static final int DOC_SUPPLIER = 20;
    static final int DOC_DATE = 10;
    static final int RULE_KIND = 90;
    static final int MAX = 100;

    /** Payout date window: ± days around the booking date. */
    static final int PAYOUT_DAYS = 7;
    /** Document date window relative to the booking date: [−60, +30] days. */
    static final int DOC_DAYS_BEFORE = 60;
    static final int DOC_DAYS_AFTER = 30;
    /** Minimum normalized invoice-number length to look for in a title (avoids trivial hits). */
    static final int MIN_INVOICE_LENGTH = 3;

    private static final BigDecimal DOC_AMOUNT_TOLERANCE = new BigDecimal("0.01");
    private static final Pattern PROJECT_REF = Pattern.compile("(?i)(?<![A-Z0-9])FM-(\\d{1,18})(?![A-Z0-9])");
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]");
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");
    private static final Pattern SPACES_DASHES = Pattern.compile("[\\s-]");

    private final MatchThresholds thresholds;

    public RuleMatcher(MatchThresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    /** A matcher with {@link MatchThresholds#DEFAULTS}. */
    public RuleMatcher() {
        this(MatchThresholds.DEFAULTS);
    }

    public MatchThresholds thresholds() {
        return thresholds;
    }

    /**
     * The matcher output.
     *
     * @param decision the decision
     * @param ranked   every candidate with a positive score, ranked
     * @param selected the auto-applied candidate (AUTO_APPLY, size 1), the suggestions (SUGGEST,
     *                 1..maxSuggestions), or empty (NONE)
     */
    public record MatchResult(Decision decision, List<MatchCandidate> ranked, List<MatchCandidate> selected) {

        public MatchResult {
            Objects.requireNonNull(decision, "decision");
            ranked = List.copyOf(ranked);
            selected = List.copyOf(selected);
        }

        /** The auto-applied candidate, when {@link Decision#AUTO_APPLY}. */
        public Optional<MatchCandidate> auto() {
            return decision == Decision.AUTO_APPLY ? Optional.of(selected.get(0)) : Optional.empty();
        }
    }

    /** Scores every candidate against the line and applies the decision rule. */
    public MatchResult match(TxnFacts txn, MatchInputs inputs) {
        Objects.requireNonNull(txn, "txn");
        MatchInputs in = inputs == null ? MatchInputs.empty() : inputs;
        List<CounterpartyRule> rules = applicableRules(txn, in.rules());

        Map<String, MatchCandidate> best = new LinkedHashMap<>();
        internalTransfers(txn, in.ownAccounts()).forEach(c -> keepBest(best, c));
        ruleKindCandidates(txn, rules).forEach(c -> keepBest(best, c));
        if (txn.direction() == TxnFacts.Direction.INCOME) {
            clientPayments(txn, in.schedules(), rules).forEach(c -> keepBest(best, c));
        } else {
            for (PayoutCandidate p : in.payouts()) {
                scorePayout(txn, p, rules).ifPresent(c -> keepBest(best, c));
            }
            for (DocumentCandidate d : in.documents()) {
                scoreProcurement(txn, d, rules).ifPresent(c -> keepBest(best, c));
            }
        }

        Set<String> allowed = ClassifierAnswerValidator.allowedKinds(txn.direction().name());
        List<MatchCandidate> ranked = new ArrayList<>();
        for (MatchCandidate c : best.values()) {
            if (allowed.contains(c.kind().name()) && c.score().signum() > 0) {
                ranked.add(c);
            }
        }
        ranked.sort(MatchCandidate.RANKING);
        return decide(ranked, thresholds);
    }

    /** Applies the decision rule to an already {@link MatchCandidate#RANKING ranked} list. */
    public static MatchResult decide(List<MatchCandidate> ranked, MatchThresholds t) {
        List<BigDecimal> scores = ranked.stream().map(MatchCandidate::score).toList();
        Decision decision = decision(scores, t);
        List<MatchCandidate> selected = switch (decision) {
            case AUTO_APPLY -> List.of(ranked.get(0));
            case SUGGEST -> ranked.stream()
                    .filter(c -> c.score().compareTo(t.suggestThreshold()) >= 0)
                    .limit(t.maxSuggestions())
                    .toList();
            case NONE -> List.of();
        };
        return new MatchResult(decision, ranked, selected);
    }

    /**
     * The decision rule over scores sorted descending (also used by reverse document matching,
     * where the ranked items are ledger lines scored against one document).
     */
    public static Decision decision(List<BigDecimal> scoresDesc, MatchThresholds t) {
        if (scoresDesc.isEmpty()) {
            return Decision.NONE;
        }
        BigDecimal top = scoresDesc.get(0);
        BigDecimal second = scoresDesc.size() > 1 ? scoresDesc.get(1) : BigDecimal.ZERO;
        if (top.compareTo(t.autoThreshold()) >= 0 && top.subtract(second).compareTo(t.margin()) >= 0) {
            return Decision.AUTO_APPLY;
        }
        if (top.compareTo(t.suggestThreshold()) >= 0 && t.maxSuggestions() > 0) {
            return Decision.SUGGEST;
        }
        return Decision.NONE;
    }

    // ---------------------------------------------------------------- internal transfer (R14.2)

    private static List<MatchCandidate> internalTransfers(TxnFacts txn, List<OwnAccount> own) {
        String iban = IbanNormalizer.clean(txn.counterpartyIban());
        if (iban == null) {
            return List.of();
        }
        List<MatchCandidate> out = new ArrayList<>();
        for (OwnAccount a : own) {
            if (iban.equals(IbanNormalizer.clean(a.iban()))) {
                out.add(candidate(LinkKind.INTERNAL_TRANSFER, new TargetRef(TargetType.OWN_ACCOUNT, a.accountId(), null),
                        INTERNAL_TRANSFER, List.of(R_OWN_ACCOUNT)));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- counterparty rules (R14.3)

    private static List<CounterpartyRule> applicableRules(TxnFacts txn, List<CounterpartyRule> rules) {
        if (txn.counterpartyKey() == null || txn.counterpartyKey().isBlank()) {
            return List.of();
        }
        return rules.stream()
                .filter(r -> r.enabled() && txn.counterpartyKey().equals(r.counterpartyKey()))
                .toList();
    }

    /** OTHER_EXPENSE / OTHER_INCOME / INTERNAL_TRANSFER targets taken straight from a rule: 0.90. */
    private static List<MatchCandidate> ruleKindCandidates(TxnFacts txn, List<CounterpartyRule> rules) {
        List<MatchCandidate> out = new ArrayList<>();
        for (CounterpartyRule r : rules) {
            switch (r.targetKind()) {
                case OTHER_EXPENSE, OTHER_INCOME -> {
                    if (r.unplannedCategoryId() != null) {
                        out.add(candidate(r.targetKind(),
                                new TargetRef(TargetType.UNPLANNED_CATEGORY, r.unplannedCategoryId(), r.projectId()),
                                RULE_KIND, List.of(R_RULE_HIT)));
                    }
                }
                case INTERNAL_TRANSFER -> {
                    if (r.counterAccountId() != null) {
                        out.add(candidate(LinkKind.INTERNAL_TRANSFER,
                                new TargetRef(TargetType.OWN_ACCOUNT, r.counterAccountId(), null),
                                RULE_KIND, List.of(R_RULE_HIT)));
                    }
                }
                default -> {
                    // WORKER_PAYOUT / PROCUREMENT / CLIENT_PAYMENT rules boost scored candidates.
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- client payment (R14.4)

    private static List<MatchCandidate> clientPayments(TxnFacts txn, List<ScheduleCandidate> schedules,
                                                       List<CounterpartyRule> rules) {
        Set<Long> refs = projectReferences(txn.title());
        List<MatchCandidate> out = new ArrayList<>();
        for (ScheduleCandidate s : schedules) {
            if (!s.published()) {
                continue;
            }
            List<String> reasons = new ArrayList<>();
            int points = 0;
            if (nameMatches(txn, s.clientName()) || nipMatches(txn, s.clientNip())) {
                points += CLIENT_NAME;
                reasons.add(R_CLIENT_MATCH);
            }
            if (s.openTrancheRemainders().stream().anyMatch(r -> sameAmount(txn.amount(), r))) {
                points += CLIENT_TRANCHE;
                reasons.add(R_TRANCHE_AMOUNT);
            }
            if (rules.stream().anyMatch(r -> r.targetKind() == LinkKind.CLIENT_PAYMENT && ruleHitsSchedule(r, s))) {
                points += CLIENT_RULE;
                reasons.add(R_RULE_HIT);
            }
            if (refs.contains(s.projectId()) && points < PROJECT_REFERENCE) {
                points = PROJECT_REFERENCE;
                reasons.add(0, R_PROJECT_REFERENCE);
            } else if (refs.contains(s.projectId())) {
                reasons.add(0, R_PROJECT_REFERENCE);
            }
            if (points > 0) {
                out.add(candidate(LinkKind.CLIENT_PAYMENT,
                        new TargetRef(TargetType.PAYMENT_SCHEDULE, s.scheduleId(), s.projectId()), points, reasons));
            }
        }
        return out;
    }

    private static boolean ruleHitsSchedule(CounterpartyRule r, ScheduleCandidate s) {
        if (r.paymentScheduleId() != null) {
            return r.paymentScheduleId() == s.scheduleId();
        }
        return r.projectId() != null && r.projectId() == s.projectId();
    }

    /** Project ids referenced as {@code FM-{projectId}} in a title (case-insensitive). */
    public static Set<Long> projectReferences(String title) {
        Set<Long> ids = new TreeSet<>();
        if (title == null) {
            return ids;
        }
        Matcher m = PROJECT_REF.matcher(title);
        while (m.find()) {
            try {
                ids.add(Long.parseLong(m.group(1)));
            } catch (NumberFormatException ignored) {
                // Out of range — not a valid reference.
            }
        }
        return ids;
    }

    // ---------------------------------------------------------------- worker payout (R14.5)

    /**
     * Scores one payout: only {@code BANK_TRANSFER} payouts, {@code PLANNED} or {@code PAID} not
     * fully linked, on an {@code EXPENSE} line.
     */
    public static Optional<MatchCandidate> scorePayout(TxnFacts txn, PayoutCandidate p, List<CounterpartyRule> rules) {
        if (txn.direction() != TxnFacts.Direction.EXPENSE
                || p.paymentChannel() != PaymentChannel.BANK_TRANSFER
                || p.status() == null
                || (p.status() == PayoutStatus.PAID && p.fullyLinked())) {
            return Optional.empty();
        }
        List<String> reasons = new ArrayList<>();
        int points = 0;
        if (p.amount() != null && sameAmount(txn.amount(), p.amount())) {
            points += PAYOUT_AMOUNT;
            reasons.add(R_AMOUNT);
        }
        if (p.payoutDate() != null
                && Math.abs(p.payoutDate().toEpochDay() - txn.bookingDate().toEpochDay()) <= PAYOUT_DAYS) {
            points += PAYOUT_DATE;
            reasons.add(R_DATE);
        }
        List<CounterpartyRule> applicable = applicableRules(txn, rules);
        boolean ruleHit = applicable.stream()
                .anyMatch(r -> r.targetKind() == LinkKind.WORKER_PAYOUT && r.workerId() != null
                        && r.workerId() == p.workerId());
        if (ruleHit) {
            points += PAYOUT_RULE;
            reasons.add(R_RULE_HIT);
        } else if (nameMatches(txn, p.workerName())) {
            points += PAYOUT_NAME;
            reasons.add(R_NAME);
        }
        if (points > 0 && p.status() == PayoutStatus.PAID) {
            points += PAYOUT_PAID_PENALTY;
            reasons.add(R_ALREADY_PAID);
        }
        if (points <= 0) {
            return Optional.empty();
        }
        return Optional.of(candidate(LinkKind.WORKER_PAYOUT,
                new TargetRef(TargetType.WORKER_PAYOUT, p.payoutId(), null), points, reasons));
    }

    // ---------------------------------------------------------------- procurement (R14.6, R28.2)

    /**
     * Single-document procurement scorer (also used by reverse document matching, R28.2). Only
     * documents in {@code UNMATCHED} / {@code PARTIALLY_PAID}, on an {@code EXPENSE} line.
     *
     * @param rules counterparty rules; a matching enabled {@code PROCUREMENT} rule for the document's
     *              supplier counts as the supplier signal
     */
    public static Optional<MatchCandidate> scoreProcurement(TxnFacts txn, DocumentCandidate d,
                                                            List<CounterpartyRule> rules) {
        if (txn.direction() != TxnFacts.Direction.EXPENSE
                || (d.paymentStatus() != DocumentPaymentStatus.UNMATCHED
                    && d.paymentStatus() != DocumentPaymentStatus.PARTIALLY_PAID)) {
            return Optional.empty();
        }
        List<String> reasons = new ArrayList<>();
        int points = 0;
        if (d.documentTotal() != null
                && txn.amount().subtract(d.documentTotal().abs()).abs().compareTo(DOC_AMOUNT_TOLERANCE) <= 0) {
            points += DOC_AMOUNT;
            reasons.add(R_AMOUNT);
        }
        if (invoiceNumberInTitle(d.invoiceNumber(), txn.title())) {
            points += DOC_INVOICE;
            reasons.add(R_INVOICE_NUMBER);
        }
        List<CounterpartyRule> applicable = rules == null ? List.of() : applicableRules(txn, rules);
        boolean ruleHit = applicable.stream()
                .anyMatch(r -> r.targetKind() == LinkKind.PROCUREMENT && ruleHitsSupplier(r, d));
        if (ruleHit || nipMatches(txn, d.supplierNip()) || nameMatches(txn, d.supplierName())) {
            points += DOC_SUPPLIER;
            reasons.add(ruleHit ? R_RULE_HIT : R_SUPPLIER);
        }
        if (inDocWindow(d.invoiceDate(), txn.bookingDate()) || inDocWindow(d.uploadDate(), txn.bookingDate())) {
            points += DOC_DATE;
            reasons.add(R_DATE);
        }
        if (points <= 0) {
            return Optional.empty();
        }
        return Optional.of(candidate(LinkKind.PROCUREMENT,
                new TargetRef(TargetType.PROCUREMENT_DOCUMENT, d.documentId(), null), points, reasons));
    }

    private static boolean ruleHitsSupplier(CounterpartyRule r, DocumentCandidate d) {
        String ruleNip = digits(r.supplierNip());
        String docNip = digits(d.supplierNip());
        if (ruleNip != null && ruleNip.equals(docNip)) {
            return true;
        }
        return r.supplierName() != null && d.supplierName() != null
                && NameSimilarity.similar(r.supplierName(), d.supplierName());
    }

    private static boolean inDocWindow(LocalDate docDate, LocalDate booking) {
        if (docDate == null) {
            return false;
        }
        return !docDate.isBefore(booking.minusDays(DOC_DAYS_BEFORE))
                && !docDate.isAfter(booking.plusDays(DOC_DAYS_AFTER));
    }

    static boolean invoiceNumberInTitle(String invoiceNumber, String title) {
        if (invoiceNumber == null || title == null) {
            return false;
        }
        String inv = alnum(invoiceNumber);
        return inv.length() >= MIN_INVOICE_LENGTH && alnum(title).contains(inv);
    }

    // ---------------------------------------------------------------- helpers

    private static void keepBest(Map<String, MatchCandidate> best, MatchCandidate c) {
        String key = c.kind() + "|" + c.targetRef().asString();
        MatchCandidate prev = best.get(key);
        if (prev == null) {
            best.put(key, c);
            return;
        }
        int cmp = c.score().compareTo(prev.score());
        // Equal scores: keep the lexicographically smaller rationale so input order never matters.
        if (cmp > 0 || (cmp == 0 && c.rationale().compareTo(prev.rationale()) < 0)) {
            best.put(key, c);
        }
    }

    private static MatchCandidate candidate(LinkKind kind, TargetRef ref, int points, List<String> reasons) {
        int capped = Math.max(0, Math.min(MAX, points));
        return new MatchCandidate(kind, ref, BigDecimal.valueOf(capped, 2), reasons);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        return a.abs().setScale(2, RoundingMode.HALF_UP).compareTo(b.abs().setScale(2, RoundingMode.HALF_UP)) == 0;
    }

    private static boolean nameMatches(TxnFacts txn, String name) {
        return name != null && txn.counterpartyName() != null && NameSimilarity.similar(txn.counterpartyName(), name);
    }

    /** A 10-digit NIP found in the counterparty name or title (spaces / dashes ignored). */
    private static boolean nipMatches(TxnFacts txn, String nip) {
        String n = digits(nip);
        if (n == null || n.length() != 10) {
            return false;
        }
        return containsDigits(txn.counterpartyName(), n) || containsDigits(txn.title(), n);
    }

    private static boolean containsDigits(String text, String digits) {
        return text != null && SPACES_DASHES.matcher(text).replaceAll("").contains(digits);
    }

    private static String digits(String s) {
        if (s == null) {
            return null;
        }
        String d = NON_DIGIT.matcher(s).replaceAll("");
        return d.isEmpty() ? null : d;
    }

    private static String alnum(String s) {
        return NON_ALNUM.matcher(s.toUpperCase(Locale.ROOT)).replaceAll("");
    }
}
