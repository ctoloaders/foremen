package com.foremen.service.bank.detect;

import com.foremen.dao.model.LinkKind;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * FOR-07-04 (Requirement 14, design "RuleMatcher") — one scored link proposal produced by the
 * {@link RuleMatcher}.
 *
 * @param kind      the link kind the candidate would create
 * @param targetRef the link target
 * @param score     the score in {@code [0.00, 1.00]} (scale 2)
 * @param reasons   machine-readable reason codes (see {@code RuleMatcher.R_*}), in scoring order
 */
public record MatchCandidate(LinkKind kind, TargetRef targetRef, BigDecimal score, List<String> reasons) {

    /** What a {@link TargetRef#id()} points to. */
    public enum TargetType {
        /** {@code bank_accounts.id} — the counter account of an internal transfer. */
        OWN_ACCOUNT,
        /** {@code payment_schedules.id}. */
        PAYMENT_SCHEDULE,
        /** {@code worker_payouts.id}. */
        WORKER_PAYOUT,
        /** {@code procurement_documents.id}. */
        PROCUREMENT_DOCUMENT,
        /** {@code unplanned_expense_categories.id} (OTHER_EXPENSE / OTHER_INCOME). */
        UNPLANNED_CATEGORY
    }

    /**
     * A link target.
     *
     * @param type      what {@code id} refers to
     * @param id        the target id
     * @param projectId the optional project (e.g. for OTHER_EXPENSE / CLIENT_PAYMENT); may be null
     */
    public record TargetRef(TargetType type, long id, Long projectId) {

        public TargetRef {
            Objects.requireNonNull(type, "type");
        }

        /** Opaque, stable string form used as the AI {@code candidateRef}. */
        public String asString() {
            return projectId == null ? type + ":" + id : type + ":" + id + "@P" + projectId;
        }
    }

    /** Deterministic ranking: score desc, then kind, target type, target id, project id. */
    public static final Comparator<MatchCandidate> RANKING = Comparator
            .comparing(MatchCandidate::score, Comparator.reverseOrder())
            .thenComparing(MatchCandidate::kind)
            .thenComparing(c -> c.targetRef().type())
            .thenComparingLong(c -> c.targetRef().id())
            .thenComparing(c -> c.targetRef().projectId(),
                    Comparator.nullsFirst(Comparator.naturalOrder()));

    public MatchCandidate {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetRef, "targetRef");
        Objects.requireNonNull(score, "score");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** Short human-readable rationale (reason codes joined), used for stored suggestions. */
    public String rationale() {
        return String.join(", ", reasons);
    }
}
