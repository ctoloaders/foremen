package com.foremen.service.signing.merge;

import com.foremen.exception.ForemenApiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * FOR-05-08 (Requirements 3.3, 3.4, 3.8; design §Components {@code TemplateMergeEngine}, Property 6):
 * the default {@link TemplateMergeEngine} — a pure, plain-string {@code {Token}} substitution over
 * the injected ordered set of {@link MergeFieldResolver}s.
 *
 * <p><b>Resolution.</b> The body is scanned once for {@code {Token}} placeholders (a {@code {}, a
 * run of characters that are neither {@code {} nor {@code }}, then a {@code }}). For each distinct
 * placeholder the engine asks the <b>first</b> resolver that {@link MergeFieldResolver#supports
 * supports} the (trimmed) token name to {@link MergeFieldResolver#resolve resolve} it. A non-{@code
 * null} value substitutes the placeholder; a {@code null} (or no supporting resolver) marks the
 * token unresolved. The scan is single-pass over the <i>template</i> text, so a resolved value that
 * itself contains braces is never re-interpreted as a placeholder.
 *
 * <p><b>Unresolved handling (Requirement 3.4, never silent empty).</b> Every unresolved token is
 * collected into the returned {@link MergeResult#unresolvedPlaceholders()} in first-appearance
 * order. The caller-selected {@link MergeMode} then decides the body:
 *
 * <ul>
 *   <li>{@link MergeMode#MARK_BLANK} — each unresolved {@code {Token}} is replaced with the sentinel
 *       {@code «___»} and the render succeeds;</li>
 *   <li>{@link MergeMode#FAIL} — if any token is unresolved the render throws {@code 422
 *       error.document.unresolved.placeholders} with the sorted token list, substituting
 *       nothing.</li>
 * </ul>
 *
 * <p>An unresolved token is <b>never</b> replaced with an empty string, so a missing value can never
 * masquerade as a legitimately-blank resolved value (Property 6).
 *
 * <p><b>Determinism.</b> For a fixed {@code (templateBody, context, mode)} the output body and the
 * unresolved set are identical on every call: resolution is a pure function of the resolvers (which
 * are themselves pure) and the single left-to-right scan.
 */
@Component
public class DefaultTemplateMergeEngine implements TemplateMergeEngine {

    static final String UNRESOLVED_PLACEHOLDERS_MESSAGE = "error.document.unresolved.placeholders";

    /** The human-visible blank sentinel left for an unresolved token in {@link MergeMode#MARK_BLANK}. */
    static final String BLANK_SENTINEL = "«___»";

    /**
     * Matches a single {@code {Token}} placeholder: an opening brace, a non-empty run of characters
     * that are neither brace, then a closing brace. Group 1 is the raw inner token (trimmed before
     * it is handed to resolvers).
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    /**
     * The resolvers, in Spring-injection order; the first one that {@link
     * MergeFieldResolver#supports supports} a token wins. Spring supplies all {@code @Component}
     * resolvers; the order is stable for a given application context, which is sufficient because
     * each token is owned by exactly one resolver group.
     */
    private final List<MergeFieldResolver> resolvers;

    public DefaultTemplateMergeEngine(List<MergeFieldResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }

    @Override
    public MergeResult render(String templateBody, MergeContext context, MergeMode mode) {
        if (templateBody == null) {
            throw new IllegalArgumentException("templateBody must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }

        Set<String> unresolved = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(templateBody);
        StringBuilder rendered = new StringBuilder();

        while (matcher.find()) {
            String token = matcher.group(1).trim();
            String value = resolve(token, context);
            String replacement;
            if (value != null) {
                replacement = value;
            } else {
                unresolved.add(token);
                // Never substitute empty text for a missing value: mark a blank (MARK_BLANK) or, in
                // FAIL mode, keep the original placeholder in the partial build — it is discarded by
                // the thrown error below and never returned to the caller.
                replacement = mode == MergeMode.MARK_BLANK ? BLANK_SENTINEL : matcher.group();
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);

        if (mode == MergeMode.FAIL && !unresolved.isEmpty()) {
            List<String> sorted = new ArrayList<>(unresolved);
            sorted.sort(String::compareTo);
            throw new ForemenApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    UNRESOLVED_PLACEHOLDERS_MESSAGE,
                    String.join(", ", sorted));
        }

        return new MergeResult(rendered.toString(), unresolved);
    }

    /**
     * Resolves a single token against the context via the first supporting resolver.
     *
     * @return the resolved value, or {@code null} when no resolver supports the token or the
     *         supporting resolver returns {@code null} (an unresolved placeholder)
     */
    private String resolve(String token, MergeContext context) {
        for (MergeFieldResolver resolver : resolvers) {
            if (resolver.supports(token)) {
                return resolver.resolve(token, context);
            }
        }
        return null;
    }
}
