package com.foremen.service.procurement;

import com.foremen.dao.model.MatchStatus;

/**
 * FOR-07-03 (Requirement 4.6) — pure, total derivation of a line's {@link LinkStatus}:
 * {@code NO_PROJECT} when the document has no project; else {@code LINKED} when the match status is
 * {@code AUTO} or {@code MANUAL_MATCHED}; else {@code CLASSIFIED} when an unplanned category is
 * present; else {@code UNLINKED}.
 */
public final class LinkStatusResolver {

    private LinkStatusResolver() {
    }

    /**
     * @param hasProject            whether the owning document has a project
     * @param matchStatus           the line's match status (a null value is treated as unmatched)
     * @param hasUnplannedCategory  whether the line carries an unplanned-expense category
     * @return exactly one {@link LinkStatus}
     */
    public static LinkStatus resolve(boolean hasProject, MatchStatus matchStatus, boolean hasUnplannedCategory) {
        if (!hasProject) {
            return LinkStatus.NO_PROJECT;
        }
        if (matchStatus == MatchStatus.AUTO || matchStatus == MatchStatus.MANUAL_MATCHED) {
            return LinkStatus.LINKED;
        }
        return hasUnplannedCategory ? LinkStatus.CLASSIFIED : LinkStatus.UNLINKED;
    }
}
