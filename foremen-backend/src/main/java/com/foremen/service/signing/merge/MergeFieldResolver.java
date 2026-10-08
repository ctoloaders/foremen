package com.foremen.service.signing.merge;

/**
 * FOR-05-08 (Requirements 3.3, 3.4, 3.8, 14.4; design §Components {@code TemplateMergeEngine +
 * MergeFieldResolver}): a pluggable resolver for one group of template {@code {Token}} placeholders.
 *
 * <p>The {@link com.foremen.service.signing.merge.TemplateMergeEngine} owns the ordered set of
 * resolvers; for each token found in a template it asks the first resolver that {@link
 * #supports(String) supports} the token to {@link #resolve(String, MergeContext) resolve} it against
 * the {@link MergeContext}. Keeping each placeholder group behind its own resolver makes the merge
 * layer <b>decoupled from the signing lifecycle</b>: a new document type or placeholder is added as a
 * resolver + seed change, with no change to the signing code (Requirements 3.8, 14.4; design key
 * decision 5).
 *
 * <p><b>Null means unresolved, never silent empty.</b> {@link #resolve} returns {@code null} (not an
 * empty string) when the token's backing datum is absent from the context, so the engine can report
 * it as an unresolved placeholder and apply the caller-selected unresolved behaviour (mark a blank
 * or fail) — it must never substitute empty text that looks like real, resolved data (Requirement
 * 3.4, Property 6). A resolver must only be asked to {@link #resolve} a token it {@link #supports}.
 *
 * <p>Implementations are pure and stateless (Spring {@code @Component}s), so the whole merge
 * resolution is deterministic for a fixed {@code (template, MergeContext)} (Property 6).
 */
public interface MergeFieldResolver {

    /**
     * Whether this resolver handles the given template token.
     *
     * @param token the placeholder token name <b>without</b> the surrounding braces (e.g.
     *              {@code "ContactName"} for the template placeholder {@code {ContactName}}); never
     *              {@code null}
     * @return {@code true} iff {@link #resolve(String, MergeContext)} can be called with this token
     */
    boolean supports(String token);

    /**
     * Resolves a supported token to its substitution value from the context.
     *
     * @param token the placeholder token name without braces; the caller guarantees {@link
     *              #supports(String) supports(token)} is {@code true}
     * @param ctx   the aggregated domain snapshot to read from; never {@code null}
     * @return the resolved substitution value, or {@code null} when the backing datum is absent from
     *         {@code ctx} (an unresolved placeholder) — never an empty string standing in for a
     *         missing value (Requirement 3.4)
     */
    String resolve(String token, MergeContext ctx);
}
