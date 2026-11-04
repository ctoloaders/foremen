package com.foremen.service.bank.link;

import java.math.BigDecimal;
import java.util.Objects;

import com.foremen.dao.model.DetectionSource;
import com.foremen.service.bank.detect.MatchCandidate;

/**
 * FOR-07-04 (Requirement 13.7) — a suggestion to store on a ledger line (detection 6.8). Suggestions
 * never have side effects until confirmed through {@link BankLinkService#confirmSuggestion}.
 *
 * @param target     the target shape (kind, amount, targets); a {@code null} amount → the default
 * @param source     {@code RULE} or {@code AI}
 * @param confidence 0..1, may be {@code null}
 * @param rationale  short text (≤ 300 chars, truncated)
 */
public record SuggestionDraft(LinkRequest target, DetectionSource source, BigDecimal confidence,
                              String rationale) {

    public SuggestionDraft {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
    }

    /** A {@code RULE} suggestion for a matcher candidate (confidence = score, rationale = reasons). */
    public static SuggestionDraft fromCandidate(MatchCandidate candidate) {
        return new SuggestionDraft(LinkRequest.fromCandidate(candidate), DetectionSource.RULE,
                candidate.score(), candidate.rationale());
    }
}
