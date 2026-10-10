package com.foremen.service.readiness;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.service.offer.OfferVisibilityResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OfferReadinessContributor}'s status → state/percentage mapping and
 * current-offer selection (FOR-05-11 Requirement 5.3, 5.7).
 *
 * <p>The derivation is exercised directly through its pure, Spring-free entry points
 * ({@link OfferReadinessContributor#deriveContribution} and
 * {@link OfferReadinessContributor#currentOffer}) with a real {@link OfferVisibilityResolver}
 * (itself a pure collaborator), so no persistence or Spring context is needed.
 *
 * <p>Feature: FOR-05-11-project-readiness
 *
 * <p><b>Validates: Requirements 5.3, 5.7</b>
 */
class OfferReadinessContributorTest {

    private final OfferVisibilityResolver resolver = new OfferVisibilityResolver();

    @Test
    void approvedOfferIsDoneAtHundredWithNoItems() {
        AreaContribution contribution =
                OfferReadinessContributor.deriveContribution(OfferStatus.APPROVED, resolver);

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.DONE);
        assertThat(contribution.percentage()).isEqualTo(100);
        assertThat(contribution.items()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = OfferStatus.class,
            names = {"SENT", "CHANGES_REQUESTED", "COUNTERED"})
    void onApprovalOfferIsPartialAtFiftyWithOnApprovalItem(OfferStatus status) {
        AreaContribution contribution = OfferReadinessContributor.deriveContribution(status, resolver);

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.PARTIAL);
        assertThat(contribution.percentage()).isEqualTo(50);
        assertThat(contribution.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionKey()).isEqualTo("readiness.offer.onApproval");
            assertThat(item.params()).isEmpty();
            assertThat(item.targetTab()).isEqualTo("pricing");
        });
    }

    @ParameterizedTest
    @EnumSource(value = OfferStatus.class, names = {"DRAFT", "REJECTED", "WITHDRAWN"})
    void draftOrTerminalDeadOfferIsBlockedAtZeroWithNotReadyItem(OfferStatus status) {
        AreaContribution contribution = OfferReadinessContributor.deriveContribution(status, resolver);

        assertBlockedNotReady(contribution);
    }

    @Test
    void noOfferIsBlockedAtZeroWithNotReadyItem() {
        AreaContribution contribution = OfferReadinessContributor.deriveContribution(null, resolver);

        assertBlockedNotReady(contribution);
    }

    @Test
    void itemsAreEmptyIffDone() {
        for (OfferStatus status : OfferStatus.values()) {
            AreaContribution contribution = OfferReadinessContributor.deriveContribution(status, resolver);
            boolean fullyReady =
                    contribution.state() == ReadinessState.DONE && contribution.percentage() == 100;
            assertThat(contribution.items().isEmpty())
                    .as("items empty iff done for status %s", status)
                    .isEqualTo(fullyReady);
        }
    }

    // --- current offer selection ---

    @Test
    void currentOfferIsEmptyWhenNoOffers() {
        assertThat(OfferReadinessContributor.currentOffer(List.of())).isEmpty();
    }

    @Test
    void currentOfferPrefersTheLatestNonTerminalOffer() {
        OfferEntity rejected = offer(1L, OfferStatus.REJECTED);
        OfferEntity sent = offer(2L, OfferStatus.SENT);

        Optional<OfferEntity> current = OfferReadinessContributor.currentOffer(List.of(rejected, sent));

        assertThat(current).containsSame(sent);
    }

    @Test
    void currentOfferPrefersApprovedOverOtherTerminalWhenNoNonTerminal() {
        OfferEntity approved = offer(1L, OfferStatus.APPROVED);
        OfferEntity withdrawn = offer(2L, OfferStatus.WITHDRAWN);

        Optional<OfferEntity> current = OfferReadinessContributor.currentOffer(List.of(approved, withdrawn));

        assertThat(current).containsSame(approved);
    }

    @Test
    void currentOfferFallsBackToLatestTerminalWhenNoNonTerminalAndNoApproved() {
        OfferEntity rejected = offer(1L, OfferStatus.REJECTED);
        OfferEntity withdrawn = offer(2L, OfferStatus.WITHDRAWN);

        Optional<OfferEntity> current = OfferReadinessContributor.currentOffer(List.of(rejected, withdrawn));

        assertThat(current).containsSame(withdrawn);
    }

    @Test
    void offerAreaKeyOrderResourceAndTargetTabAreStable() {
        OfferReadinessContributor contributor = new OfferReadinessContributor(null, resolver);

        assertThat(contributor.areaKey()).isEqualTo("offer");
        assertThat(contributor.areaResource()).isEqualTo("OFFERS");
        assertThat(contributor.order()).isEqualTo(30);
    }

    // --- helpers ---

    private static void assertCommonShape(AreaContribution contribution) {
        assertThat(contribution.key()).isEqualTo("offer");
        assertThat(contribution.nameKey()).isEqualTo("workspace.tab.offer");
        assertThat(contribution.percentage()).isBetween(0, 100);
    }

    private static void assertBlockedNotReady(AreaContribution contribution) {
        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.BLOCKED);
        assertThat(contribution.percentage()).isZero();
        assertThat(contribution.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionKey()).isEqualTo("readiness.offer.notReady");
            assertThat(item.params()).isEmpty();
            assertThat(item.targetTab()).isEqualTo("pricing");
        });
    }

    private static OfferEntity offer(long id, OfferStatus status) {
        OfferEntity offer = new OfferEntity();
        offer.setId(id);
        offer.setStatus(status);
        return offer;
    }
}
