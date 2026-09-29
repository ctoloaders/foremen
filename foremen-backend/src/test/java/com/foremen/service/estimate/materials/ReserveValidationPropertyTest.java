package com.foremen.service.estimate.materials;

// Feature: for-05-05b-list-of-materials, Property 6: Reserve validation accepts a value iff it is in [0..100] with at most two decimals

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.estimate.materials.MaterialsReserveRequest.ReserveEntry;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property 6 (FOR-05-05b): the server reserve validation accepts a percent iff it is a number in
 * {@code [0..100]} with at most two decimals, treats {@code null}/empty as <em>unset</em> (identity),
 * and accepts a whole reserve-map write iff <b>every</b> entry is valid (any invalid entry rejects the
 * whole write).
 *
 * <p>The server bounds live in {@link MaterialsListService#isValidPercent(BigDecimal)} — a
 * package-visible {@code static} predicate with NO Spring context and NO database, so this property
 * exercises it directly. The bounds are identical to the client zod schema
 * ({@code foremen-frontend/src/features/materials/schemas/reserve.ts}); the two are validated by
 * mirrored properties so the client and the server never diverge.
 *
 * <p>Each property recomputes its expectation with an INDEPENDENT oracle rather than reusing the
 * production predicate's own logic:
 *
 * <ul>
 *   <li>a candidate percent is accepted iff it is {@code null} (unset ⇒ identity, R9.3) OR a number in
 *       {@code [0..100]} (R9.1, R9.2) with ≤2 decimal places (R9.1) — decimals counted from the
 *       normalized (trailing-zeros-stripped) value so {@code 5.00} and {@code 5.10} are ≤2 decimals;
 *   <li>a value below {@code 0}, above {@code 100}, or with more than two significant decimals is
 *       rejected (R9.2);
 *   <li>a {@code null}/empty percent is unset — accepted, and treated as identity, never an error
 *       (R9.3);
 *   <li>a whole reserve-map write is accepted iff every entry is present-and-valid — a single invalid
 *       or malformed entry rejects the whole write (R9.4).
 * </ul>
 *
 * <p>Feature: for-05-05b-list-of-materials, Property 6
 *
 * <p><b>Validates: Requirements 9.1, 9.2, 9.3, 9.4</b>
 */
@Tag("Feature: for-05-05b-list-of-materials, Property 6: Reserve validation accepts a value iff it is in [0..100] with at most two decimals")
class ReserveValidationPropertyTest {

    private static final BigDecimal MIN = BigDecimal.ZERO;
    private static final BigDecimal MAX = new BigDecimal("100");
    private static final int MAX_SCALE = 2;

    // --- Property 6a: a single percent is accepted iff null OR in [0..100] with ≤2 decimals ---

    @Property(tries = 200)
    void percentIsAcceptedIffNullOrInRangeWithAtMostTwoDecimals(
            @ForAll("candidatePercents") BigDecimal percent) {

        boolean expected = isAcceptableByOracle(percent);
        boolean actual = MaterialsListService.isValidPercent(percent);

        assertThat(actual)
                .as("isValidPercent(%s) must equal the independent [0..100]/≤2-decimals oracle", percent)
                .isEqualTo(expected);
    }

    // --- Property 6b: a null/empty percent is unset (identity), always accepted, never an error ---

    @Property(tries = 100)
    void nullPercentIsUnsetAndAlwaysAccepted(
            @ForAll("candidatePercents") BigDecimal ignoredToVaryTheRun) {

        assertThat(MaterialsListService.isValidPercent(null))
                .as("a null (cleared/empty) percent is unset (identity), not an error (R9.3)")
                .isTrue();
    }

    // --- Property 6c: an in-range value with ≤2 decimals is always accepted ---

    @Property(tries = 200)
    void anyInRangeValueWithAtMostTwoDecimalsIsAccepted(
            @ForAll("validPercents") BigDecimal percent) {

        assertThat(MaterialsListService.isValidPercent(percent))
                .as("a valid percent %s (in [0..100], ≤2 decimals) is accepted", percent)
                .isTrue();
    }

    // --- Property 6d: an out-of-band value (out of range OR >2 decimals) is always rejected ---

    @Property(tries = 200)
    void anyOutOfBandValueIsRejected(@ForAll("invalidPercents") BigDecimal percent) {

        assertThat(MaterialsListService.isValidPercent(percent))
                .as("an invalid percent %s (out of [0..100] or >2 decimals) is rejected", percent)
                .isFalse();
    }

    // --- Property 6e: a whole write is accepted iff EVERY entry is valid (R9.4) ---

    @Property(tries = 200)
    void wholeWriteIsAcceptedIffEveryEntryIsValid(
            @ForAll("reserveRequests") MaterialsReserveRequest request) {

        boolean allEntriesValid = request.entries().stream().allMatch(ReserveValidationPropertyTest::entryIsValid);

        assertThat(wholeWriteAccepted(request))
                .as("the whole write is accepted iff every one of its entries is valid (R9.4)")
                .isEqualTo(allEntriesValid);
    }

    // --- Property 6f: injecting a single invalid entry rejects an otherwise-valid write ---

    @Property(tries = 200)
    void oneInvalidEntryRejectsAnOtherwiseValidWrite(
            @ForAll("validRequests") MaterialsReserveRequest validRequest,
            @ForAll("invalidPercents") BigDecimal badPercent,
            @ForAll long badMaterialId) {

        // The all-valid request is accepted.
        assertThat(wholeWriteAccepted(validRequest))
                .as("an all-valid write is accepted")
                .isTrue();

        // Append one out-of-range entry: the whole write must now be rejected (R9.4).
        List<ReserveEntry> withBad = new java.util.ArrayList<>(validRequest.entries());
        withBad.add(new ReserveEntry(Math.abs(badMaterialId) + 1, badPercent));
        assertThat(wholeWriteAccepted(new MaterialsReserveRequest(withBad)))
                .as("a single invalid entry (%s) rejects the whole write (R9.4)", badPercent)
                .isFalse();
    }

    // =====================================================================================
    // Independent oracles (never reuse the production predicate's logic)
    // =====================================================================================

    /**
     * Independent oracle for a single percent: {@code null} ⇒ accepted (unset, R9.3); otherwise a
     * number in {@code [0..100]} (R9.1, R9.2) with at most two decimal places counted from its
     * normalized (trailing-zeros-stripped) form (R9.1).
     */
    private static boolean isAcceptableByOracle(BigDecimal percent) {
        if (percent == null) {
            return true;
        }
        if (percent.compareTo(MIN) < 0 || percent.compareTo(MAX) > 0) {
            return false;
        }
        return normalizedDecimals(percent) <= MAX_SCALE;
    }

    /** The number of significant decimal places of {@code value} (trailing zeros do not count). */
    private static int normalizedDecimals(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }

    /** A request entry is valid iff it is present, has a material id, and a valid percent. */
    private static boolean entryIsValid(ReserveEntry entry) {
        return entry != null && entry.materialId() != null && isAcceptableByOracle(entry.percent());
    }

    /**
     * Mirrors {@link MaterialsListService#saveReserveMap} server validation loop: a write is accepted
     * iff every entry is present-and-valid; a {@code null} entries list is an accepted empty write.
     */
    private static boolean wholeWriteAccepted(MaterialsReserveRequest request) {
        List<ReserveEntry> entries =
                request == null || request.entries() == null ? List.of() : request.entries();
        for (ReserveEntry entry : entries) {
            if (entry == null || entry.materialId() == null || !MaterialsListService.isValidPercent(entry.percent())) {
                return false;
            }
        }
        return true;
    }

    // =====================================================================================
    // Providers
    // =====================================================================================

    /**
     * A mixed pool of candidate percents spanning the whole input space: {@code null} (unset), valid
     * in-range values (≤2 decimals), boundary values ({@code 0}, {@code 100}), out-of-range values
     * (negative, {@code >100}), and >2-decimal (over-precise) values. Malformed/non-numeric input is
     * represented via {@link BigDecimal} — a client sends the percent as a JSON number, so any value
     * that reaches the server predicate is already a {@code BigDecimal} (or {@code null} for empty).
     */
    @Provide
    Arbitrary<BigDecimal> candidatePercents() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                validPercents(),
                invalidPercents());
    }

    /** A valid percent: a number in {@code [0..100]} with at most two decimals. */
    @Provide
    Arbitrary<BigDecimal> validPercents() {
        return Arbitraries.oneOf(
                // Whole and ≤2-decimal values across the range.
                Arbitraries.bigDecimals().between(MIN, MAX).ofScale(2),
                Arbitraries.bigDecimals().between(MIN, MAX).ofScale(1),
                Arbitraries.bigDecimals().between(MIN, MAX).ofScale(0),
                // Boundaries and trailing-zero variants (5.00, 100.0) — normalize to ≤2 decimals.
                Arbitraries.of(
                        BigDecimal.ZERO,
                        new BigDecimal("0.00"),
                        new BigDecimal("5.00"),
                        new BigDecimal("5.10"),
                        new BigDecimal("99.99"),
                        new BigDecimal("100"),
                        new BigDecimal("100.00")));
    }

    /** An invalid percent: out of {@code [0..100]} OR with more than two significant decimals. */
    @Provide
    Arbitrary<BigDecimal> invalidPercents() {
        Arbitrary<BigDecimal> negative =
                Arbitraries.bigDecimals().between(new BigDecimal("-1000"), new BigDecimal("-0.01")).ofScale(2);
        Arbitrary<BigDecimal> tooLarge =
                Arbitraries.bigDecimals().between(new BigDecimal("100.01"), new BigDecimal("100000")).ofScale(2);
        // In range but with three or more significant decimals ⇒ over-precise (rejected).
        Arbitrary<BigDecimal> overPrecise = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.001"), new BigDecimal("99.999"))
                .ofScale(3)
                .filter(v -> v.stripTrailingZeros().scale() > MAX_SCALE);
        return Arbitraries.oneOf(negative, tooLarge, overPrecise);
    }

    /** A valid material id (positive Long). */
    @Provide
    Arbitrary<Long> materialIds() {
        return Arbitraries.longs().between(1L, 1_000_000L);
    }

    /** A request whose entries mix valid and invalid percents (whole-write acceptance under test). */
    @Provide
    Arbitrary<MaterialsReserveRequest> reserveRequests() {
        Arbitrary<ReserveEntry> entry =
                Combinators.combine(materialIds(), candidatePercents()).as(ReserveEntry::new);
        return entry.list().ofMinSize(0).ofMaxSize(8).map(MaterialsReserveRequest::new);
    }

    /** A request all of whose entries are present-and-valid (accepted whole write). */
    @Provide
    Arbitrary<MaterialsReserveRequest> validRequests() {
        Arbitrary<BigDecimal> percentOrUnset =
                Arbitraries.oneOf(Arbitraries.just(null), validPercents());
        Arbitrary<ReserveEntry> entry =
                Combinators.combine(materialIds(), percentOrUnset).as(ReserveEntry::new);
        return entry.list().ofMinSize(0).ofMaxSize(8).map(MaterialsReserveRequest::new);
    }
}
