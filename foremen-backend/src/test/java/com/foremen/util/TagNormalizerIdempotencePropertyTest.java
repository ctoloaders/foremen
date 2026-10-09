package com.foremen.util;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 5 (FOR-05-09): Tag normalization is idempotent and round-trips through persistence.
 *
 * <p>For any submitted list of strings that is valid under Requirement 15 criterion 3, applying
 * {@link TagNormalizer#normalize(List)}:
 * <ul>
 *   <li>is idempotent — {@code normalize(normalize(x)).equals(normalize(x))};</li>
 *   <li>produces a list whose every tag has no leading or trailing whitespace;</li>
 *   <li>produces a list with no two tags equal under case-insensitive comparison, keeping the
 *       first spelling and the original relative order;</li>
 *   <li>produces at most ten tags, and never more tags than the submitted list.</li>
 * </ul>
 *
 * <p>The round-trip property ("the Assignment_Tags returned in the Team_Member_View after a save
 * equal the Tag_Normalization of the submitted list") is modeled here at the pure-helper level:
 * the normalized list is the canonical stored form, and re-normalizing that stored form yields an
 * equal list (persistence stability). The entity/service round-trip through the database is
 * exercised by the integration tests; this property verifies the pure, dependency-free contract
 * those flows share through {@link TagNormalizer}.
 *
 * <p>The generator produces only lists that are valid under Requirement 15 criterion 3 (each tag
 * trims to 1..50 code points, holds no control character, and the list normalizes to at most ten
 * tags), so every generated input is accepted and the structural guarantees above must hold.
 *
 * <p>Feature: FOR-05-09-team-selection, Property 5: Tag normalization is idempotent and round-trips
 *
 * <p><b>Validates: Requirements 15.4, 15.3</b>
 */
@Tag("Feature: FOR-05-09-team-selection, Property 5: Tag normalization is idempotent and round-trips")
class TagNormalizerIdempotencePropertyTest {

    private static final int MAX_TAG_CODE_POINTS = 50;
    private static final int MAX_TAGS = 10;

    // ------------------------------------------------------------------------------------------
    // Property 5a: Tag_Normalization is idempotent — normalize(normalize(x)) == normalize(x).
    // ------------------------------------------------------------------------------------------
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 5: Tag normalization is idempotent and round-trips")
    void normalizationIsIdempotent(@ForAll("validTagLists") List<String> submitted) {
        List<String> once = TagNormalizer.normalize(submitted);
        List<String> twice = TagNormalizer.normalize(once);

        assertThat(twice).isEqualTo(once);
    }

    // ------------------------------------------------------------------------------------------
    // Property 5b: the normalized list round-trips — re-normalizing the stored (canonical) form
    // returns an equal list, so a Team_Member_View rebuilt from the stored tags equals the
    // Tag_Normalization of the submitted list.
    // ------------------------------------------------------------------------------------------
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 5: Tag normalization is idempotent and round-trips")
    void storedFormRoundTripsEqualToNormalization(@ForAll("validTagLists") List<String> submitted) {
        List<String> normalized = TagNormalizer.normalize(submitted);

        // Simulate persist -> read: the stored list is the normalized list; the view rebuilds it.
        List<String> stored = new ArrayList<>(normalized);
        List<String> viewTags = TagNormalizer.normalize(stored);

        assertThat(viewTags).isEqualTo(normalized);
    }

    // ------------------------------------------------------------------------------------------
    // Property 5c: no surviving tag carries leading or trailing whitespace (inner whitespace kept).
    // ------------------------------------------------------------------------------------------
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 5: Tag normalization is idempotent and round-trips")
    void noTagHasLeadingOrTrailingWhitespace(@ForAll("validTagLists") List<String> submitted) {
        List<String> normalized = TagNormalizer.normalize(submitted);

        for (String tag : normalized) {
            assertThat(tag).isEqualTo(tag.strip());
            assertThat(tag).isNotEmpty();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 5d: no two surviving tags are equal under case-insensitive comparison, and the
    // first spelling in the original order is the one kept.
    // ------------------------------------------------------------------------------------------
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 5: Tag normalization is idempotent and round-trips")
    void noCaseInsensitiveDuplicatesKeepingFirstSpellingAndOrder(
            @ForAll("validTagLists") List<String> submitted) {
        List<String> normalized = TagNormalizer.normalize(submitted);

        // Pairwise distinct under case-insensitive comparison.
        for (int i = 0; i < normalized.size(); i++) {
            for (int j = i + 1; j < normalized.size(); j++) {
                assertThat(normalized.get(i).equalsIgnoreCase(normalized.get(j)))
                        .as("tags %d and %d are case-insensitive duplicates", i, j)
                        .isFalse();
            }
        }

        // The normalized list equals an independent reference dedup (first spelling, original order).
        assertThat(normalized).isEqualTo(referenceNormalize(submitted));
    }

    // ------------------------------------------------------------------------------------------
    // Property 5e: at most ten tags, and never more tags than were submitted.
    // ------------------------------------------------------------------------------------------
    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 5: Tag normalization is idempotent and round-trips")
    void atMostTenTagsAndNoMoreThanSubmitted(@ForAll("validTagLists") List<String> submitted) {
        List<String> normalized = TagNormalizer.normalize(submitted);

        assertThat(normalized.size()).isLessThanOrEqualTo(MAX_TAGS);
        assertThat(normalized.size()).isLessThanOrEqualTo(submitted.size());
    }

    // ------------------------------------------------------------------------------------------
    // Independent reference implementation of Tag_Normalization: trim each tag, drop a tag equal to
    // an earlier one under case-insensitive comparison (keep the first spelling and original order).
    // ------------------------------------------------------------------------------------------
    private static List<String> referenceNormalize(List<String> submitted) {
        List<String> result = new ArrayList<>();
        for (String raw : submitted) {
            String trimmed = raw.strip();
            boolean duplicate = false;
            for (String existing : result) {
                if (existing.equalsIgnoreCase(trimmed)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result.add(trimmed);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------
    // Providers
    // ------------------------------------------------------------------------------------------

    /**
     * Generates tag lists that are valid under Requirement 15 criterion 3: every element trims to
     * 1..50 code points, holds no Unicode control character, and the whole list normalizes to at
     * most ten tags. Padding tags with leading/trailing whitespace and mixed-case near-duplicates
     * exercises the trim and case-insensitive dedup branches.
     */
    @Provide
    Arbitrary<List<String>> validTagLists() {
        return rawTags().list().ofMinSize(0).ofMaxSize(12)
                .filter(list -> referenceNormalize(list).size() <= MAX_TAGS);
    }

    /**
     * A single raw (pre-trim) tag whose trimmed form has 1..50 code points and no control
     * character. A non-whitespace, non-control core (several scripts, digits, punctuation) is
     * optionally wrapped in leading/trailing whitespace so the trim step has something to remove,
     * while the trimmed length stays within 1..50 code points. Including mixed-case ASCII cores
     * exercises the case-insensitive dedup branch across repeated draws.
     */
    private Arbitrary<String> rawTags() {
        return rawValidTagCore()
                .flatMap(core -> net.jqwik.api.Combinators
                        .combine(whitespacePad(), Arbitraries.just(core), whitespacePad())
                        .as((lead, body, trail) -> lead + body + trail))
                .filter(s -> {
                    String t = s.strip();
                    int cp = t.codePointCount(0, t.length());
                    return cp >= 1 && cp <= MAX_TAG_CODE_POINTS
                            && t.codePoints().noneMatch(Character::isISOControl);
                });
    }

    /**
     * The non-whitespace, non-control core of a valid tag (1..50 printable code points), drawn from
     * printable BMP and some supplementary code points, plus a bias toward short mixed-case ASCII
     * words so that case-insensitive duplicates occur often enough to exercise the dedup branch.
     */
    private Arbitrary<String> rawValidTagCore() {
        Arbitrary<Integer> codePoints = Arbitraries.integers()
                .between(0x21, 0x1F64F)
                .filter(cp -> Character.isValidCodePoint(cp)
                        && !Character.isISOControl(cp)
                        && !Character.isWhitespace(cp));
        Arbitrary<String> freeform = codePoints.list().ofMinSize(1).ofMaxSize(MAX_TAG_CODE_POINTS)
                .map(cps -> {
                    StringBuilder sb = new StringBuilder();
                    cps.forEach(sb::appendCodePoint);
                    return sb.toString();
                })
                .filter(s -> s.codePointCount(0, s.length()) <= MAX_TAG_CODE_POINTS);
        // Short mixed-case ASCII words make case-insensitive near-duplicates likely.
        Arbitrary<String> casedWords = Arbitraries.of("Java", "java", "JAVA", "Spec", "spec",
                "Tag", "TAG", "Alfa", "alfa", "Beta", "BETA");
        return Arbitraries.oneOf(casedWords, freeform);
    }

    /** Zero-or-more leading/trailing whitespace fragments that {@code String.strip()} removes. */
    private Arbitrary<String> whitespacePad() {
        return Arbitraries.of("", " ", "  ", "\t", " \t ")
                .list().ofMaxSize(2)
                .map(parts -> String.join("", parts));
    }
}
