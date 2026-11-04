package com.foremen.service.procurement;

/**
 * FOR-07-03 (Requirement 4.6) — the derived, never-stored linking state of a procurement line in the
 * company-wide purchases view. Computed by {@link LinkStatusResolver}.
 *
 * <p>Not to be confused with {@code com.foremen.dao.model.LinkStatus} (bank-transaction linking).
 */
public enum LinkStatus {
    /** The owning document has no project. */
    NO_PROJECT,
    /** Matched to a planned material ({@code AUTO} / {@code MANUAL_MATCHED}). */
    LINKED,
    /** Not matched and no unplanned-expense category. */
    UNLINKED,
    /** Not matched but classified under an unplanned-expense category. */
    CLASSIFIED
}
