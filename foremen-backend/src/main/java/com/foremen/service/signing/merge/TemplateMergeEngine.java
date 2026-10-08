package com.foremen.service.signing.merge;

/**
 * FOR-05-08 (Requirements 3.3, 3.4, 3.8, 14.4; design §Components {@code TemplateMergeEngine +
 * MergeFieldResolver}, design key decision 5): the named, unit-testable component that renders a
 * concrete document body from a template body carrying {@code {Token}} merge placeholders and a
 * {@link MergeContext} of resolved domain data.
 *
 * <p>The engine scans the template body for {@code {Token}} placeholders and, for each, asks the
 * first {@link MergeFieldResolver} that {@link MergeFieldResolver#supports(String) supports} the
 * token to resolve it. A resolved token is substituted with its value; an unresolved token (no
 * resolver, or the resolver returned {@code null}) is collected into the returned
 * {@link MergeResult#unresolvedPlaceholders() unresolved set} and handled per the caller-selected
 * {@link MergeMode} — <b>never</b> silently replaced with empty text that looks like real data
 * (Requirement 3.4, Property 6).
 *
 * <h2>Toolkit isolation (design Open Question 4)</h2>
 *
 * <p>The engine's contract is deliberately expressed over a plain {@code String} body, not a
 * document-format type: the DRAFT body is the editable HTML/text surface of the Requirement 3a
 * editor, and the merge is a plain {@code {Token}} string substitution. Any DOCX/OOXML concern
 * (reading a {@code .docx} template into its text body, or the later HTML/DOCX→PDF freeze) lives
 * <b>outside</b> this engine — in {@code DocumentTemplateService}'s {@code .docx} import and in
 * {@code PdfFreezeService}. Keeping the format toolkit behind those boundaries means the merge
 * logic (and its property test) is pure and the toolkit can be swapped without touching the
 * lifecycle.
 *
 * <h2>Determinism</h2>
 *
 * <p>For a fixed {@code (templateBody, MergeContext, MergeMode)} the result is deterministic: the
 * same body and the same unresolved set every time (Property 6). The unresolved set preserves
 * first-appearance order so callers and tests see a stable ordering.
 */
public interface TemplateMergeEngine {

    /**
     * Renders {@code templateBody} by substituting its {@code {Token}} placeholders from
     * {@code context}.
     *
     * @param templateBody the template source body carrying {@code {Token}} placeholders (the
     *                     stored DRAFT body text / HTML); never {@code null}
     * @param context      the aggregated domain snapshot the resolvers read from; never {@code null}
     * @param mode         how to handle placeholders that no resolver resolved — mark a blank or
     *                     fail (Requirement 3.4); never {@code null}
     * @return the rendered body plus the deterministic set of unresolved placeholders
     * @throws com.foremen.exception.ForemenApiException 422 {@code
     *         error.document.unresolved.placeholders} (carrying the sorted unresolved token list)
     *         when {@code mode} is {@link MergeMode#FAIL} and at least one placeholder is unresolved
     *         (Requirement 3.4)
     */
    MergeResult render(String templateBody, MergeContext context, MergeMode mode);
}
