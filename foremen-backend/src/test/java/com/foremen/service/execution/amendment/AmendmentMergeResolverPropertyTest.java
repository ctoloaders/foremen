package com.foremen.service.execution.amendment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;

import com.foremen.dao.model.ChangeType;
import com.foremen.exception.ForemenApiException;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-06-08 (Requirement 5) — property-based coverage of the pure, persistence-free
 * {@link AmendmentMergeResolver}. The resolver owns the {@code Amendment_Subject} identity and the
 * {@code Merge_Resolution} arithmetic that collapse two same-subject change lines into one surviving
 * line; it performs no I/O and holds no state, so every rule here is exercised in isolation with no
 * Spring / persistence context (design §Testing strategy "Pure merge core").
 *
 * <p>The properties mirror the design's enumerated merge-core properties one-to-one:
 *
 * <ol>
 *   <li><b>Single-line-per-subject</b> — after resolving a same-subject conflict with any
 *       {@link MergeResolution}, exactly one line survives per {@code Amendment_Subject}: the returned
 *       line is always the (mutated-in-place) {@code existing} and its subject is unchanged
 *       (Requirement 5.4).</li>
 *   <li><b>{@code ADD_TO} schedule commutativity / associativity</b> — summing signed half-day
 *       deltas with {@code ADD_TO} on {@code SCHEDULE_CHANGE} is commutative and associative; the
 *       surviving half-day delta equals the plain integer sum regardless of fold order
 *       (Requirement 5.2).</li>
 *   <li><b>{@code KEEP_EXISTING} idempotence</b> — {@code KEEP_EXISTING} returns {@code existing}
 *       untouched for any incoming input, and applying it repeatedly leaves the line unchanged
 *       (Requirement 5.4).</li>
 *   <li><b>{@code ADD_TO} / {@code REPLACE} volume reject-on-negative</b> — a cost-line resolution
 *       whose resulting volume ({@code baselineQuantity + resulting deltaQuantity}) is negative is
 *       rejected with a 400 {@code error.amendment.volumeNegative}; a non-negative result succeeds
 *       (Requirement 5.3 / 3.6).</li>
 *   <li><b>Subject identity per {@link ChangeType}</b> — {@code SCHEDULE_CHANGE} keys on
 *       {@code workCategoryId} (a {@code null} category is a significant, equal value); cost lines
 *       ({@code ADDITIONAL_WORK} / {@code VOLUME_CHANGE}) key on {@code (workItemId, roomId)}; a
 *       difference in any keyed component makes the subjects distinct, and change types never share a
 *       subject (Requirement 5.1, 5.2, 5.3, 5.5).</li>
 * </ol>
 *
 * <p>Feature: FOR-06-08-amendments, merge core
 *
 * <p><b>Validates: Requirements 5.1, 5.2, 5.3, 5.4</b>
 */
@Tag("Feature: FOR-06-08-amendments, merge core")
class AmendmentMergeResolverPropertyTest {

    private final AmendmentMergeResolver resolver = new AmendmentMergeResolver();

    // --- Property 1: single-line-per-subject after any resolution (Requirement 5.4) -------------

    /**
     * For any same-subject conflict and any {@link MergeResolution}, the resolution yields a single
     * surviving line: {@code resolve} returns the very {@code existing} instance (REPLACE/ADD_TO mutate
     * it in place, KEEP_EXISTING leaves it untouched) and the surviving subject still equals the
     * original subject — so at most one line per {@code Amendment_Subject} can remain.
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-06-08-amendments, merge core")
    void anyResolutionLeavesExactlyOneLinePerSubject(
            @ForAll("sameSubjectConflicts") Conflict conflict,
            @ForAll MergeResolution resolution) {

        AmendmentSubject subjectBefore = resolver.subjectOf(conflict.existing);

        AmendmentLineDelta survivor = resolver.resolve(conflict.existing, conflict.incoming, resolution);

        // Exactly one line survives, and it is the existing line (the single-line-per-subject rule).
        assertThat(survivor).isSameAs(conflict.existing);
        // Its subject is preserved: a resolution never re-keys the surviving line onto a new subject.
        assertThat(resolver.subjectOf(survivor)).isEqualTo(subjectBefore);
        // The survivor is still in conflict with the incoming input it merged (same subject),
        // confirming the subject has not drifted.
        assertThat(resolver.conflicts(survivor, conflict.incoming)).isTrue();
    }

    // --- Property 2: ADD_TO schedule commutativity / associativity (Requirement 5.2) ------------

    /**
     * {@code ADD_TO} on {@code SCHEDULE_CHANGE} sums the signed half-day deltas, which is commutative:
     * folding {@code a} then {@code b} yields the same surviving half-day total as folding {@code b}
     * then {@code a}, and both equal the plain integer sum {@code a + b}.
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-06-08-amendments, merge core")
    void addToScheduleIsCommutative(
            @ForAll("halfDays") int a,
            @ForAll("halfDays") int b,
            @ForAll("categoryId") Long categoryId) {

        int forward = foldSchedule(categoryId, a, b);
        int reversed = foldSchedule(categoryId, b, a);

        assertThat(forward).isEqualTo(a + b);
        assertThat(reversed).isEqualTo(a + b);
        assertThat(forward).isEqualTo(reversed);
    }

    /**
     * {@code ADD_TO} on {@code SCHEDULE_CHANGE} is associative: {@code (a + b) + c} equals
     * {@code a + (b + c)} however the three same-subject half-day deltas are folded, and both equal the
     * plain integer sum {@code a + b + c}.
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-06-08-amendments, merge core")
    void addToScheduleIsAssociative(
            @ForAll("halfDays") int a,
            @ForAll("halfDays") int b,
            @ForAll("halfDays") int c,
            @ForAll("categoryId") Long categoryId) {

        int leftAssoc = foldSchedule(categoryId, foldSchedule(categoryId, a, b), c);
        int rightAssoc = foldSchedule(categoryId, a, foldSchedule(categoryId, b, c));

        assertThat(leftAssoc).isEqualTo(a + b + c);
        assertThat(rightAssoc).isEqualTo(a + b + c);
        assertThat(leftAssoc).isEqualTo(rightAssoc);
    }

    /** Fold two half-day schedule deltas on the same subject via {@code ADD_TO}, returning the sum. */
    private int foldSchedule(Long categoryId, int first, int second) {
        AmendmentLineDelta existing = scheduleLine(categoryId, first);
        AmendmentLineDelta incoming = scheduleLine(categoryId, second);
        AmendmentLineDelta survivor = resolver.resolve(existing, incoming, MergeResolution.ADD_TO);
        return survivor.getScheduleDeltaHalfDays();
    }

    // --- Property 3: KEEP_EXISTING idempotence (Requirement 5.4) --------------------------------

    /**
     * {@code KEEP_EXISTING} discards the incoming input and returns the existing line untouched, for
     * any same-subject incoming line. Applying it repeatedly is idempotent — the surviving half-day /
     * quantity never changes.
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-06-08-amendments, merge core")
    void keepExistingReturnsExistingUnchangedAndIsIdempotent(
            @ForAll("sameSubjectConflicts") Conflict conflict) {

        Integer halfDaysBefore = conflict.existing.getScheduleDeltaHalfDays();
        BigDecimal deltaBefore = conflict.existing.getDeltaQuantity();
        BigDecimal priceBefore = conflict.existing.getExtraUnitPrice();

        AmendmentLineDelta once =
                resolver.resolve(conflict.existing, conflict.incoming, MergeResolution.KEEP_EXISTING);
        // Re-applying KEEP_EXISTING with the same incoming input (idempotence).
        AmendmentLineDelta twice =
                resolver.resolve(once, conflict.incoming, MergeResolution.KEEP_EXISTING);

        assertThat(once).isSameAs(conflict.existing);
        assertThat(twice).isSameAs(conflict.existing);
        // The existing line's fields are all unchanged — the incoming input was discarded.
        assertThat(once.getScheduleDeltaHalfDays()).isEqualTo(halfDaysBefore);
        assertThat(once.getDeltaQuantity()).isEqualTo(deltaBefore);
        assertThat(once.getExtraUnitPrice()).isEqualTo(priceBefore);
    }

    // --- Property 4: ADD_TO / REPLACE volume reject-on-negative (Requirement 5.3 / 3.6) ---------

    /**
     * For a cost-line conflict ({@code VOLUME_CHANGE}), a resolution ({@code ADD_TO} or {@code REPLACE})
     * is rejected with a 400 {@code error.amendment.volumeNegative} exactly when the resulting volume
     * ({@code baselineQuantity + resulting deltaQuantity}) is negative, and succeeds otherwise. The
     * resulting delta is the summed delta for {@code ADD_TO} and the incoming delta for {@code REPLACE}.
     */
    @Property(tries = 400)
    @Tag("Feature: FOR-06-08-amendments, merge core")
    void volumeResolutionRejectsIffResultingVolumeNegative(
            @ForAll("quantity") BigDecimal baseline,
            @ForAll("quantity") BigDecimal existingDelta,
            @ForAll("quantity") BigDecimal incomingDelta,
            @ForAll("costResolution") MergeResolution resolution) {

        AmendmentLineDelta existing = volumeLine(baseline, existingDelta);
        AmendmentLineDelta incoming = volumeLine(baseline, incomingDelta);

        BigDecimal resultingDelta =
                resolution == MergeResolution.ADD_TO ? existingDelta.add(incomingDelta) : incomingDelta;
        BigDecimal resultingVolume = baseline.add(resultingDelta);
        boolean shouldReject = resultingVolume.signum() < 0;

        if (shouldReject) {
            assertThatThrownBy(() -> resolver.resolve(existing, incoming, resolution))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException api = (ForemenApiException) ex;
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(api.getMessageCode())
                                .isEqualTo(AmendmentMergeResolver.VOLUME_NEGATIVE);
                    });
        } else {
            AmendmentLineDelta survivor = resolver.resolve(existing, incoming, resolution);
            assertThat(survivor).isSameAs(existing);
            assertThat(survivor.getDeltaQuantity()).isEqualByComparingTo(resultingDelta);
            // Resulting volume stays non-negative (the guard's invariant).
            assertThat(survivor.getBaselineQuantity().add(survivor.getDeltaQuantity()).signum())
                    .isGreaterThanOrEqualTo(0);
        }
    }

    // --- Property 5: subject identity per ChangeType (Requirement 5.1, 5.2, 5.3, 5.5) -----------

    /**
     * {@code SCHEDULE_CHANGE} keys its subject on {@code workCategoryId}: two schedule lines conflict
     * iff their (possibly {@code null}) category ids are equal. A {@code null} category — the
     * whole-project schedule — is a significant value that equals another {@code null} category.
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-06-08-amendments, merge core")
    void scheduleSubjectKeysOnWorkCategoryIncludingNull(
            @ForAll("categoryId") Long categoryA,
            @ForAll("categoryId") Long categoryB) {

        AmendmentLineDelta a = scheduleLine(categoryA, 1);
        AmendmentLineDelta b = scheduleLine(categoryB, -1);

        boolean expectedSameSubject = java.util.Objects.equals(categoryA, categoryB);

        assertThat(resolver.conflicts(a, b)).isEqualTo(expectedSameSubject);
        assertThat(resolver.subjectOf(a).equals(resolver.subjectOf(b)))
                .isEqualTo(expectedSameSubject);
        // The schedule subject never carries the cost-line keys.
        assertThat(resolver.subjectOf(a).workItemId()).isNull();
        assertThat(resolver.subjectOf(a).roomId()).isNull();
    }

    /**
     * Cost lines ({@code ADDITIONAL_WORK} / {@code VOLUME_CHANGE}) key their subject on
     * {@code (workItemId, roomId)}: two same-type cost lines conflict iff BOTH the work item and the
     * room coincide; any difference makes them distinct (Requirement 5.3, 5.5). The schedule category
     * is never part of a cost-line subject.
     */
    @Property(tries = 400)
    @Tag("Feature: FOR-06-08-amendments, merge core")
    void costSubjectKeysOnWorkItemAndRoom(
            @ForAll("costType") ChangeType type,
            @ForAll("id") long workItemA,
            @ForAll("id") long roomA,
            @ForAll("id") long workItemB,
            @ForAll("id") long roomB) {

        AmendmentLineDelta a = costLine(type, workItemA, roomA);
        AmendmentLineDelta b = costLine(type, workItemB, roomB);

        boolean expectedSameSubject = workItemA == workItemB && roomA == roomB;

        assertThat(resolver.conflicts(a, b)).isEqualTo(expectedSameSubject);
        assertThat(resolver.subjectOf(a).workCategoryId()).isNull();
        assertThat(resolver.subjectOf(a).workItemId()).isEqualTo(workItemA);
        assertThat(resolver.subjectOf(a).roomId()).isEqualTo(roomA);
    }

    /**
     * Different {@link ChangeType}s never share a subject: the {@code changeType} is part of the
     * subject, so a {@code SCHEDULE_CHANGE} and a cost line — or two cost lines of different type with
     * the same {@code (workItem, room)} — are always distinct and never merge.
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-06-08-amendments, merge core")
    void differentChangeTypesNeverShareASubject(
            @ForAll("id") long workItem,
            @ForAll("id") long room) {

        AmendmentLineDelta schedule = scheduleLine(null, 2);
        AmendmentLineDelta additional = costLine(ChangeType.ADDITIONAL_WORK, workItem, room);
        AmendmentLineDelta volume = costLine(ChangeType.VOLUME_CHANGE, workItem, room);

        // Schedule vs either cost line: distinct types, never a conflict.
        assertThat(resolver.conflicts(schedule, additional)).isFalse();
        assertThat(resolver.conflicts(schedule, volume)).isFalse();
        // Two cost lines with identical (workItem, room) but different type: still distinct.
        assertThat(resolver.conflicts(additional, volume)).isFalse();
    }

    // --- Generators -----------------------------------------------------------------------------

    /** A signed half-day delta (bounded to keep int sums well clear of overflow across folds). */
    @Provide
    Arbitrary<Integer> halfDays() {
        return Arbitraries.integers().between(-10_000, 10_000);
    }

    /** A whole-project ({@code null}) or specific work-category id. */
    @Provide
    Arbitrary<Long> categoryId() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.longs().between(1L, 20L));
    }

    /** A positive entity id (work item / room). Kept small so collisions happen often. */
    @Provide
    Arbitrary<Long> id() {
        return Arbitraries.longs().between(1L, 5L);
    }

    /** A signed quantity with 4-decimal scale, spanning negatives and positives. */
    @Provide
    Arbitrary<BigDecimal> quantity() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-500.0000"), new BigDecimal("500.0000"))
                .ofScale(4);
    }

    /** The two cost-line change types. */
    @Provide
    Arbitrary<ChangeType> costType() {
        return Arbitraries.of(ChangeType.ADDITIONAL_WORK, ChangeType.VOLUME_CHANGE);
    }

    /** The two resolutions that write a new cost-line delta (and so can reject on negative volume). */
    @Provide
    Arbitrary<MergeResolution> costResolution() {
        return Arbitraries.of(MergeResolution.ADD_TO, MergeResolution.REPLACE);
    }

    /**
     * A same-subject conflict across all change types: two lines that share an {@code Amendment_Subject}
     * (and so are a valid input to {@code resolve}), with arbitrary deltas. For cost lines the deltas
     * are kept non-negative so the generated conflict is resolvable under every resolution (the
     * reject-on-negative behaviour is exercised by its own dedicated property).
     */
    @Provide
    Arbitrary<Conflict> sameSubjectConflicts() {
        Arbitrary<Conflict> schedule = Combinators.combine(
                        categoryId(), halfDays(), halfDays())
                .as((category, existing, incoming) ->
                        new Conflict(scheduleLine(category, existing), scheduleLine(category, incoming)));

        Arbitrary<Conflict> cost = Combinators.combine(
                        costType(), id(), id(), nonNegativeQuantity(), nonNegativeQuantity())
                .as((type, workItem, room, existingDelta, incomingDelta) -> new Conflict(
                        volumeLikeLine(type, workItem, room, existingDelta),
                        volumeLikeLine(type, workItem, room, incomingDelta)));

        return Arbitraries.oneOf(schedule, cost);
    }

    /** A non-negative quantity so a same-subject cost conflict is resolvable under any resolution. */
    private Arbitrary<BigDecimal> nonNegativeQuantity() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("500.0000"))
                .ofScale(4);
    }

    // --- Line builders --------------------------------------------------------------------------

    private AmendmentLineDelta scheduleLine(Long workCategoryId, int halfDays) {
        return new AmendmentLineDelta(ChangeType.SCHEDULE_CHANGE)
                .setWorkCategoryId(workCategoryId)
                .setScheduleDeltaHalfDays(halfDays);
    }

    private AmendmentLineDelta costLine(ChangeType type, long workItemId, long roomId) {
        return new AmendmentLineDelta(type)
                .setWorkItemId(workItemId)
                .setRoomId(roomId)
                .setBaselineQuantity(BigDecimal.ZERO)
                .setDeltaQuantity(BigDecimal.ONE)
                .setExtraUnitPrice(BigDecimal.TEN);
    }

    /** A {@code VOLUME_CHANGE} line with an explicit baseline + delta for the reject-on-negative rule. */
    private AmendmentLineDelta volumeLine(BigDecimal baseline, BigDecimal delta) {
        return new AmendmentLineDelta(ChangeType.VOLUME_CHANGE)
                .setWorkItemId(1L)
                .setRoomId(1L)
                .setBaselineQuantity(baseline)
                .setDeltaQuantity(delta)
                .setExtraUnitPrice(BigDecimal.TEN);
    }

    private AmendmentLineDelta volumeLikeLine(ChangeType type, long workItemId, long roomId, BigDecimal delta) {
        return new AmendmentLineDelta(type)
                .setWorkItemId(workItemId)
                .setRoomId(roomId)
                .setBaselineQuantity(BigDecimal.ZERO)
                .setDeltaQuantity(delta)
                .setExtraUnitPrice(BigDecimal.TEN);
    }

    /** A same-subject pair of lines fed to {@code resolve}. */
    private record Conflict(AmendmentLineDelta existing, AmendmentLineDelta incoming) {
    }
}
