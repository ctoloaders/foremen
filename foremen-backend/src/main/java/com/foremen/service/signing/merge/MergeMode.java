package com.foremen.service.signing.merge;

/**
 * FOR-05-08 (Requirement 3.4; design §Components {@code TemplateMergeEngine}): the caller-selected
 * strategy for how {@link TemplateMergeEngine#render(String, MergeContext, MergeMode) render}
 * handles template {@code {Token}} placeholders that no resolver could resolve against the
 * {@link MergeContext}.
 *
 * <p>Requirement 3.4 mandates that the unresolved behaviour be <b>deterministic and reported</b> —
 * never a silent empty substitution that looks like real, resolved data. The two modes are the two
 * explicit options of that requirement; the engine always also returns the full
 * {@link MergeResult#unresolvedPlaceholders() unresolved set} regardless of the mode.
 */
public enum MergeMode {

    /**
     * Leave a clearly marked, human-visible blank in place of each unresolved placeholder: the
     * literal {@code {Token}} is replaced with the sentinel {@code «___»} so a person can complete
     * it manually (Requirement 3.4 option (a)). Rendering always succeeds; the unresolved tokens are
     * still reported in {@link MergeResult#unresolvedPlaceholders()}.
     */
    MARK_BLANK,

    /**
     * Fail the whole render with {@code 422 error.document.unresolved.placeholders} carrying the
     * sorted list of unresolved tokens, substituting nothing (Requirement 3.4 option (b)). Use this
     * when the caller wants a complete document or no document at all.
     */
    FAIL
}
