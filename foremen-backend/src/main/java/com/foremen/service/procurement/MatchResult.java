package com.foremen.service.procurement;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;

/**
 * FOR-06-03 (design §5.3, Requirements 1.5, 5.5, 5.8) — the outcome of matching one
 * {@link ExtractedLine} against the project's candidate set.
 *
 * <p>Invariant (R1.5): {@code matchedMaterialId == null} if and only if {@code tier == NONE} (and
 * then {@code status == UNMATCHED}); at any non-{@code NONE} tier {@code matchedMaterialId} is the
 * winning candidate's id and {@code status == AUTO}. The engine always returns a result honoring
 * this invariant; {@link MatchEngine#satisfiesInvariant(MatchResult)} re-checks it for the service's
 * pre-persist assertion.
 */
public record MatchResult(Long matchedMaterialId, MatchTier tier, MatchStatus status) {
}
