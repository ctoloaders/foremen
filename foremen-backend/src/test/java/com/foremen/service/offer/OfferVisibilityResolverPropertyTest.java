package com.foremen.service.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;

import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.OfferVisibilityStatus;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link OfferVisibilityResolver#visibilityOf(OfferStatus)} — the pure,
 * total, deterministic projection of an {@link OfferStatus} onto its client-facing
 * {@link OfferVisibilityStatus} per the Requirement 17.4 mapping (FOR-05-07, design §Property 10).
 *
 * <p>The resolver is exercised directly as a pure function — no persistence, no Spring context — so
 * Property 10 is cheap to run over 100+ iterations.
 *
 * <p>Feature: FOR-05-07-offer-approval, Property 10: Offer visibility is a consistent projection of
 * the offer status
 *
 * <p><b>Validates: Requirements 17.4, 17.9</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 10: Offer visibility is a consistent projection of the offer status")
class OfferVisibilityResolverPropertyTest {

    /** Statuses the Requirement 17.4 mapping assigns a visibility projection to. */
    private static final Set<OfferStatus> MAPPED_STATUSES = EnumSet.of(
            OfferStatus.DRAFT,
            OfferStatus.SENT,
            OfferStatus.CHANGES_REQUESTED,
            OfferStatus.COUNTERED,
            OfferStatus.APPROVED);

    /** Terminal statuses R17.4 leaves undefined (dead offers with no client-visibility window). */
    private static final Set<OfferStatus> UNMAPPED_STATUSES = EnumSet.of(
            OfferStatus.REJECTED,
            OfferStatus.WITHDRAWN);

    private final OfferVisibilityResolver resolver = new OfferVisibilityResolver();

    // ------------------------------------------------------------------------------------------
    // Property 10a: for every mapped status, visibilityOf equals the exact R17.4 projection and is
    // never null. This is the fixed mapping (DRAFT -> DRAFT; SENT/CHANGES_REQUESTED/COUNTERED ->
    // ON_APPROVAL; APPROVED -> APPROVED) and no visibility value contradicts a legal Offer_Status.
    // Validates: Requirements 17.4, 17.9
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-07-offer-approval, Property 10: Offer visibility is a consistent projection of the offer status")
    void visibilityFollowsTheFixedR174Mapping(@ForAll("mappedStatuses") OfferStatus status) {
        OfferVisibilityStatus actual = resolver.visibilityOf(status);

        OfferVisibilityStatus expected = switch (status) {
            case DRAFT -> OfferVisibilityStatus.DRAFT;
            case SENT, CHANGES_REQUESTED, COUNTERED -> OfferVisibilityStatus.ON_APPROVAL;
            case APPROVED -> OfferVisibilityStatus.APPROVED;
            case REJECTED, WITHDRAWN ->
                    throw new AssertionError("mappedStatuses generator must not yield " + status);
        };

        assertThat(actual)
                .as("visibility projection of %s", status)
                .isNotNull()
                .isEqualTo(expected);
    }

    // ------------------------------------------------------------------------------------------
    // Property 10b: the projection is a pure, deterministic total function over the mapped domain --
    // the same status always yields the same non-null visibility.
    // Validates: Requirement 17.4 (projection, not an independent state machine)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-07-offer-approval, Property 10: Offer visibility is a consistent projection of the offer status")
    void projectionIsDeterministicOverTheMappedDomain(@ForAll("mappedStatuses") OfferStatus status) {
        OfferVisibilityStatus first = resolver.visibilityOf(status);
        OfferVisibilityStatus second = resolver.visibilityOf(status);

        assertThat(second).isEqualTo(first);
    }

    // ------------------------------------------------------------------------------------------
    // Property 10c: consistency of the projection -- an ON_APPROVAL projection is reached only from
    // the negotiable window (SENT/CHANGES_REQUESTED/COUNTERED), DRAFT only from DRAFT, and APPROVED
    // only from APPROVED. No visibility value ever contradicts a legal Offer_Status.
    // Validates: Requirement 17.9
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-07-offer-approval, Property 10: Offer visibility is a consistent projection of the offer status")
    void projectionNeverContradictsTheStatus(@ForAll("mappedStatuses") OfferStatus status) {
        OfferVisibilityStatus visibility = resolver.visibilityOf(status);

        switch (visibility) {
            case DRAFT -> assertThat(status).isEqualTo(OfferStatus.DRAFT);
            case ON_APPROVAL -> assertThat(status).isIn(
                    OfferStatus.SENT, OfferStatus.CHANGES_REQUESTED, OfferStatus.COUNTERED);
            case APPROVED -> assertThat(status).isEqualTo(OfferStatus.APPROVED);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 10d: the undefined terminal statuses (REJECTED / WITHDRAWN) carry no projection --
    // the resolver rejects them rather than inventing a mapping R17.4 does not define.
    // Validates: Requirement 17.4 (faithful, total projection of only the documented states)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-07-offer-approval, Property 10: Offer visibility is a consistent projection of the offer status")
    void undefinedTerminalStatusesHaveNoProjection(@ForAll("unmappedStatuses") OfferStatus status) {
        assertThatThrownBy(() -> resolver.visibilityOf(status))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** Every {@link OfferStatus} R17.4 assigns a visibility projection to. */
    @Provide
    Arbitrary<OfferStatus> mappedStatuses() {
        return Arbitraries.of(MAPPED_STATUSES.toArray(new OfferStatus[0]));
    }

    /** The terminal statuses R17.4 leaves without a visibility projection. */
    @Provide
    Arbitrary<OfferStatus> unmappedStatuses() {
        return Arbitraries.of(UNMAPPED_STATUSES.toArray(new OfferStatus[0]));
    }
}
