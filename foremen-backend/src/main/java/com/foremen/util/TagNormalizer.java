package com.foremen.util;

import com.foremen.exception.ForemenApiException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * FOR-05-09 (Requirements 15.3, 15.4, 15.10) — pure {@code Tag_Normalization} and tag validation
 * for a {@code Project_Member}'s {@code Assignment_Tags}.
 *
 * <p>Keeping this logic in a small, dependency-free utility lets the property tests exercise the
 * exact normalization and acceptance/rejection rules with no Spring or database, and lets every
 * tag-carrying flow (assign, client invitation, add-worker, tag change, project creation) share one
 * canonical implementation.
 *
 * <p><b>Tag_Normalization</b> (Requirement 15 definition + criterion 4) transforms a submitted tag
 * list into its stored form:
 * <ol>
 *   <li>trim leading and trailing whitespace of each tag (Unicode-aware, inner whitespace kept
 *       verbatim);</li>
 *   <li>drop every tag equal to an earlier tag under case-insensitive comparison, keeping the first
 *       spelling and the original order.</li>
 * </ol>
 * Normalization preserves each surviving tag's inner whitespace and letter case exactly as
 * submitted (criterion 10) and is idempotent (criterion 4).
 *
 * <p><b>Validation</b> (criterion 3) rejects the whole list — with HTTP 400 and message code
 * {@code error.project.member.tag.invalid}, saving no part of it — when the list, or any element,
 * violates a rule:
 * <ul>
 *   <li>a {@code null} element;</li>
 *   <li>a tag with 0 code points after trimming (empty / whitespace-only);</li>
 *   <li>a tag with more than 50 code points after trimming;</li>
 *   <li>a tag containing a Unicode control character (line feed, carriage return, tab, or any
 *       other), checked after trimming;</li>
 *   <li>more than 10 tags <i>after</i> normalization (so a list of 12 entries that collapses to 10
 *       is accepted).</li>
 * </ul>
 * Character counts use Unicode code points, not UTF-16 {@code char} units, so a supplementary-plane
 * tag counts by its code points.
 *
 * <p>The "not a list of strings" and "non-string element" cases of criterion 3 are enforced at the
 * JSON-binding layer (a {@code List<String>} request field); this helper covers the remaining
 * element-, length-, control-character-, and count-level rules, plus the {@code null}-list handling.
 */
public final class TagNormalizer {

    /** Shared message code for every tag-list rejection (Requirement 15 criterion 3). */
    public static final String TAG_INVALID = "error.project.member.tag.invalid";

    private static final int MAX_TAG_CODE_POINTS = 50;
    private static final int MAX_TAGS = 10;

    private TagNormalizer() {
    }

    /**
     * Validates and normalizes a submitted tag list where an absent (null) list means "no tags".
     *
     * <p>Use this for an assign, a client invitation, an add-worker, or a project-creation entry:
     * a {@code null} or omitted list stores an empty {@code Assignment_Tag} list (Requirement 15
     * criterion 2) rather than being rejected.
     *
     * @param submitted the raw submitted tag list (may be {@code null})
     * @return the normalized, immutable-order list; an empty list when {@code submitted} is
     *         {@code null} or empty; never {@code null}
     * @throws ForemenApiException HTTP 400 {@code error.project.member.tag.invalid} when any tag or
     *         the list violates Requirement 15 criterion 3
     */
    public static List<String> normalize(List<String> submitted) {
        if (submitted == null) {
            return new ArrayList<>();
        }
        return normalizeNonNull(submitted);
    }

    /**
     * Validates and normalizes a submitted tag list where a {@code null} list is itself invalid.
     *
     * <p>Use this for a tag-change {@code Attribute_Update} (Requirement 15 criterion 5): a missing
     * or {@code null} tag list is rejected with {@code error.project.member.tag.invalid}, because
     * clearing tags requires an explicit empty list (criterion 3).
     *
     * @param submitted the raw submitted tag list (a {@code null} list is rejected)
     * @return the normalized, immutable-order list (possibly empty); never {@code null}
     * @throws ForemenApiException HTTP 400 {@code error.project.member.tag.invalid} when the list is
     *         {@code null} or any tag or the list violates Requirement 15 criterion 3
     */
    public static List<String> normalizeRequired(List<String> submitted) {
        if (submitted == null) {
            throw invalid();
        }
        return normalizeNonNull(submitted);
    }

    private static List<String> normalizeNonNull(List<String> submitted) {
        List<String> normalized = new ArrayList<>(submitted.size());
        for (String raw : submitted) {
            if (raw == null) {                                   // null element -> reject (crit. 3)
                throw invalid();
            }
            String trimmed = raw.strip();                        // Unicode-aware trim; inner kept
            int codePoints = trimmed.codePointCount(0, trimmed.length());
            if (codePoints < 1 || codePoints > MAX_TAG_CODE_POINTS) {   // 0 or >50 code points
                throw invalid();
            }
            if (hasControlCharacter(trimmed)) {                  // any Unicode control char
                throw invalid();
            }
            addUnlessDuplicate(normalized, trimmed);             // case-insensitive dedup, keep first
        }
        if (normalized.size() > MAX_TAGS) {                      // >10 after normalization
            throw invalid();
        }
        return normalized;
    }

    /** Adds {@code tag} unless an earlier tag equals it case-insensitively (keeps the first spelling). */
    private static void addUnlessDuplicate(List<String> normalized, String tag) {
        for (String existing : normalized) {
            if (existing.equalsIgnoreCase(tag)) {
                return;
            }
        }
        normalized.add(tag);
    }

    /** True when {@code value} contains any Unicode control character (code-point aware). */
    private static boolean hasControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static ForemenApiException invalid() {
        return new ForemenApiException(HttpStatus.BAD_REQUEST, TAG_INVALID);
    }
}
