package com.foremen.service.document;

import java.util.List;

/**
 * FOR-05-08 (Requirements 3.4, 3a.4): the result of a template <b>test-merge preview</b> — the
 * rendered body plus the deterministic set of placeholders that could not be resolved from the
 * supplied {@code MergeContext} (sample or a selected project's data).
 *
 * <p>Unresolved handling is deterministic and reported (R3.4): a non-empty
 * {@link #unresolvedPlaceholders} list tells the admin exactly which tokens had no value — never a
 * silent empty substitution.
 *
 * @param renderedBodyUri       the uri of the rendered preview artifact, or {@code null} when inline
 * @param renderedHtml          the inline rendered HTML preview, or {@code null} when by uri
 * @param unresolvedPlaceholders the tokens that could not be resolved (possibly empty)
 */
public record TestMergeResultDto(
        String renderedBodyUri,
        String renderedHtml,
        List<String> unresolvedPlaceholders) {
}
