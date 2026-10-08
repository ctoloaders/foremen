package com.foremen.service.signing.merge;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * FOR-05-08 (Requirements 3.3, 3.4, 3.8; design §Components {@code TemplateMergeEngine}): the
 * outcome of a template merge — the rendered document body together with the explicit set of
 * {@code {Token}} placeholders that could not be resolved.
 *
 * <p>Returning the unresolved set alongside the body is the core of Requirement 3.4: the caller
 * always learns exactly which placeholders had no backing value, whether they were marked as blanks
 * ({@link MergeMode#MARK_BLANK}) or whether the render failed ({@link MergeMode#FAIL}) — there is no
 * silent empty substitution (Property 6).
 *
 * @param body                   the rendered body with every resolved {@code {Token}} substituted
 *                               and every unresolved {@code {Token}} handled per the chosen
 *                               {@link MergeMode}; never {@code null}
 * @param unresolvedPlaceholders the token names (without braces) that no resolver resolved against
 *                               the context, deterministically ordered by first appearance in the
 *                               template; never {@code null}, may be empty
 */
public record MergeResult(String body, Set<String> unresolvedPlaceholders) {

    public MergeResult {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        unresolvedPlaceholders = unresolvedPlaceholders == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(unresolvedPlaceholders));
    }

    /**
     * @return {@code true} iff no placeholder was left unresolved (the body is fully populated)
     */
    public boolean isFullyResolved() {
        return unresolvedPlaceholders.isEmpty();
    }
}
