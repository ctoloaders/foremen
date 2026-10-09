package com.foremen.util;

import com.foremen.exception.ForemenApiException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property 6 (FOR-05-09): Tag acceptance and rejection.
 *
 * <p>For any single tag string, {@link TagNormalizer} accepts it as a tag if and only if it has 1 to
 * 50 Unicode code points after trimming and contains no Unicode control character, storing its inner
 * whitespace and letter case verbatim; and for any submitted tag list that contains a null element, a
 * tag that is empty or longer than 50 code points after trimming, a tag with a control character, or
 * more than ten tags after Tag_Normalization, the system rejects the whole list with HTTP 400
 * {@code error.project.member.tag.invalid} and stores no part of it.
 *
 * <p>The "non-string element" and "not a list" cases of Requirement 15 criterion 3 are enforced at
 * the JSON-binding layer (the request field is a {@code List<String>}), so they are not exercisable
 * at this pure-helper level and are intentionally out of scope for this property.
 *
 * <p>Feature: FOR-05-09-team-selection, Property 6
 *
 * <p><b>Validates: Requirements 15.3, 15.10</b>
 */
@Tag("Feature: FOR-05-09-team-selection, Property 6")
class TagAcceptanceRejectionPropertyTest {

    private static final int MAX_TAG_CODE_POINTS = 50;
    private static final int MAX_TAGS = 10;

    // --- Acceptance: a single valid tag is accepted and stored verbatim (case + inner whitespace) ---

    /**
     * A single tag with 1..50 code points after trimming and no control character is accepted; the
     * stored tag equals the submitted tag with only leading/trailing whitespace stripped, preserving
     * inner whitespace and letter case exactly (Requirements 15.3, 15.10).
     */
    @Property(tries = 300)
    void singleValidTagIsAcceptedAndStoredVerbatim(@ForAll("validSingleTag") String tag) {
        List<String> result = TagNormalizer.normalizeRequired(List.of(tag));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(tag.strip());
    }

    // --- Acceptance iff: a single tag is accepted exactly when it satisfies the content rules ---

    /**
     * For an arbitrary single tag string, acceptance happens if and only if, after trimming, it has
     * 1..50 code points and no control character. This nails down the "if and only if" of
     * Requirement 15.3 at the single-tag level.
     */
    @Property(tries = 500)
    void singleTagAcceptedIffWithinRules(@ForAll("anySingleTag") String tag) {
        String trimmed = tag.strip();
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        boolean controlChar = trimmed.codePoints().anyMatch(Character::isISOControl);
        boolean shouldAccept = codePoints >= 1 && codePoints <= MAX_TAG_CODE_POINTS && !controlChar;

        if (shouldAccept) {
            List<String> result = TagNormalizer.normalizeRequired(List.of(tag));
            assertThat(result).containsExactly(trimmed);
        } else {
            assertRejected(List.of(tag));
        }
    }

    // --- Rejection: a null element rejects the whole list ---

    /**
     * A list containing a {@code null} element is rejected wholesale with
     * {@code error.project.member.tag.invalid}, regardless of the surrounding valid tags
     * (Requirement 15.3). {@code List.of} forbids nulls, so an explicit {@link ArrayList} is used.
     */
    @Property(tries = 200)
    void listWithNullElementIsRejected(@ForAll("validTagList") List<String> validTags,
                                       @ForAll("nullInsertIndex") int rawIndex) {
        List<String> submitted = new ArrayList<>(validTags);
        int index = submitted.isEmpty() ? 0 : Math.floorMod(rawIndex, submitted.size() + 1);
        submitted.add(index, null);

        assertRejected(submitted);
    }

    // --- Rejection: an empty / whitespace-only tag rejects the whole list ---

    /**
     * A list containing a tag that is empty or whitespace-only (0 code points after trimming) is
     * rejected (Requirement 15.3).
     */
    @Property(tries = 200)
    void listWithEmptyOrBlankTagIsRejected(@ForAll("validTagList") List<String> validTags,
                                           @ForAll("blankTag") String blank) {
        List<String> submitted = new ArrayList<>(validTags);
        submitted.add(blank);

        assertRejected(submitted);
    }

    // --- Rejection: a tag longer than 50 code points rejects the whole list ---

    /**
     * A list containing a tag with more than 50 code points after trimming is rejected
     * (Requirement 15.3). Supplementary code points are included so the count is code-point based,
     * not UTF-16 char based.
     */
    @Property(tries = 200)
    void listWithOverlongTagIsRejected(@ForAll("validTagList") List<String> validTags,
                                       @ForAll("overlongTag") String overlong) {
        List<String> submitted = new ArrayList<>(validTags);
        submitted.add(overlong);

        assertRejected(submitted);
    }

    // --- Rejection: a tag containing a control character rejects the whole list ---

    /**
     * A list containing a tag with any Unicode control character (checked after trimming) is rejected
     * (Requirement 15.3).
     */
    @Property(tries = 200)
    void listWithControlCharacterTagIsRejected(@ForAll("validTagList") List<String> validTags,
                                               @ForAll("controlCharTag") String withControl) {
        List<String> submitted = new ArrayList<>(validTags);
        submitted.add(withControl);

        assertRejected(submitted);
    }

    // --- Rejection: more than ten tags after normalization rejects the whole list ---

    /**
     * A list that still has more than ten distinct (case-insensitively) tags after Tag_Normalization
     * is rejected (Requirement 15.3). Each tag is unique and lowercase so none collapse during dedup.
     */
    @Property(tries = 100)
    void listExceedingMaxAfterNormalizationIsRejected(@ForAll("tooManyDistinctTags") List<String> submitted) {
        assertRejected(submitted);
    }

    // --- Acceptance: 12 entries collapsing to <= 10 after dedup is accepted ---

    /**
     * A list of twelve entries that collapses to ten or fewer after case-insensitive dedup is
     * accepted: the >10 limit is applied after normalization, not before (Requirement 15.3).
     */
    @Property(tries = 100)
    void listCollapsingToAtMostTenIsAccepted(@ForAll("duplicateHeavyList") List<String> submitted) {
        List<String> result = TagNormalizer.normalizeRequired(submitted);

        assertThat(result.size()).isLessThanOrEqualTo(MAX_TAGS);
        assertThat(result).isNotEmpty();
    }

    // === Helpers ===

    private void assertRejected(List<String> submitted) {
        assertThatThrownBy(() -> TagNormalizer.normalizeRequired(submitted))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getMessageCode()).isEqualTo(TagNormalizer.TAG_INVALID);
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                });
    }

    // === Providers ===

    /** A single tag that is guaranteed valid: 1..50 code points after trim, no control characters. */
    @Provide
    Arbitrary<String> validSingleTag() {
        return rawValidTagCore()
                // optionally pad with surrounding whitespace that is stripped away
                .flatMap(core -> Combinators.combine(whitespacePad(), Arbitraries.just(core), whitespacePad())
                        .as((lead, c, trail) -> lead + c + trail))
                // ensure the trimmed core still fits 1..50 code points
                .filter(s -> {
                    String t = s.strip();
                    int cp = t.codePointCount(0, t.length());
                    return cp >= 1 && cp <= MAX_TAG_CODE_POINTS && t.codePoints().noneMatch(Character::isISOControl);
                });
    }

    /** The non-whitespace, non-control core of a valid tag (1..50 printable code points). */
    private Arbitrary<String> rawValidTagCore() {
        // Printable BMP + some supplementary code points, excluding ISO control characters.
        Arbitrary<Integer> codePoints = Arbitraries.integers()
                .between(0x21, 0x1F64F)
                .filter(cp -> Character.isValidCodePoint(cp)
                        && !Character.isISOControl(cp)
                        && !Character.isWhitespace(cp));
        return codePoints.list().ofMinSize(1).ofMaxSize(MAX_TAG_CODE_POINTS)
                .map(cps -> {
                    StringBuilder sb = new StringBuilder();
                    cps.forEach(sb::appendCodePoint);
                    return sb.toString();
                })
                // code-point length may exceed 50 if we allow inner spaces later; keep core <= 50
                .filter(s -> s.codePointCount(0, s.length()) <= MAX_TAG_CODE_POINTS);
    }

    /** Zero-or-more leading/trailing whitespace fragments that {@code String.strip()} removes. */
    private Arbitrary<String> whitespacePad() {
        return Arbitraries.of("", " ", "  ", "\t", " \t ")
                .list().ofMaxSize(2)
                .map(parts -> String.join("", parts));
    }

    /** An arbitrary single tag string: may be valid or invalid; used to test the iff. */
    @Provide
    Arbitrary<String> anySingleTag() {
        return Arbitraries.oneOf(
                validSingleTag(),
                blankTag(),
                overlongTag(),
                controlCharTag(),
                Arbitraries.strings().ofMaxLength(60));
    }

    /** A list of 0..8 valid single tags (kept small so appended invalid tags stay under limits). */
    @Provide
    Arbitrary<List<String>> validTagList() {
        return validSingleTag().list().ofMinSize(0).ofMaxSize(8);
    }

    /** An index at which to insert a null element. */
    @Provide
    Arbitrary<Integer> nullInsertIndex() {
        return Arbitraries.integers().between(0, 100);
    }

    /** A tag that is empty or whitespace-only (0 code points after trimming). */
    @Provide
    Arbitrary<String> blankTag() {
        return Arbitraries.of("", " ", "   ", "\t", " \t \t ", "\n", "  \r  ");
    }

    /** A tag with more than 50 code points after trimming (code-point counted). */
    @Provide
    Arbitrary<String> overlongTag() {
        Arbitrary<Integer> count = Arbitraries.integers().between(MAX_TAG_CODE_POINTS + 1, 80);
        return count.map(n -> "x".repeat(n));
    }

    /** A tag that contains an ISO control character even after trimming. */
    @Provide
    Arbitrary<String> controlCharTag() {
        Arbitrary<Character> control = Arbitraries.chars()
                .filter(c -> Character.isISOControl(c) && !Character.isWhitespace(c));
        return Combinators.combine(
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10),
                        control,
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10))
                .as((pre, c, post) -> pre + c + post);
    }

    /** Eleven distinct lowercase tags that survive dedup (so >10 after normalization). */
    @Provide
    Arbitrary<List<String>> tooManyDistinctTags() {
        return Arbitraries.integers().between(MAX_TAGS + 1, MAX_TAGS + 6)
                .map(n -> {
                    List<String> tags = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        tags.add("tag" + i);
                    }
                    return tags;
                });
    }

    /** Twelve entries that collapse to at most ten after case-insensitive dedup. */
    @Provide
    Arbitrary<List<String>> duplicateHeavyList() {
        // Build a base of 6 unique tags, then duplicate (varying case) to reach 12 entries.
        List<String> base = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            base.add("role" + i);
        }
        List<String> twelve = new ArrayList<>(base);
        for (int i = 0; i < 6; i++) {
            twelve.add(("role" + i).toUpperCase()); // case-insensitive duplicate of base[i]
        }
        return Arbitraries.just(twelve);
    }
}
