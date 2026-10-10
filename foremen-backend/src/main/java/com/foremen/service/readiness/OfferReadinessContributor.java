package com.foremen.service.readiness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.foremen.dao.OfferDao;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.OfferVisibilityStatus;
import com.foremen.service.offer.OfferVisibilityResolver;

/**
 * The {@code offer} area's readiness contribution (FOR-05-11 Requirement 5.3).
 *
 * <p>Reports where the project's offer stands on the way to an approved, ready-to-sign proposal.
 * The area's state is driven by the <b>current offer</b> projected onto its client-facing
 * {@link OfferVisibilityStatus} through the FOR-05-07 {@link OfferVisibilityResolver}:
 *
 * <ul>
 *   <li>{@link OfferVisibilityStatus#APPROVED} (offer {@code APPROVED}) → {@link ReadinessState#DONE},
 *       {@code 100%}, no items (the offer is agreed and ready to sign).</li>
 *   <li>{@link OfferVisibilityStatus#ON_APPROVAL} (offer {@code SENT} / {@code CHANGES_REQUESTED} /
 *       {@code COUNTERED}) → {@link ReadinessState#PARTIAL}, {@code 50%}, one
 *       {@code readiness.offer.onApproval} item (the offer is sent and on approval).</li>
 *   <li>otherwise — no offer at all, or the offer is {@code DRAFT} /
 *       {@link OfferVisibilityStatus#CLOSED} (terminal {@code REJECTED} / {@code WITHDRAWN}) →
 *       {@link ReadinessState#BLOCKED}, {@code 0%}, one {@code readiness.offer.notReady} item
 *       (prepare / send the offer).</li>
 * </ul>
 *
 * <p>The <b>current offer</b> is resolved from {@link OfferDao#findByProjectIdOrderByIdAsc(Long)}
 * (oldest first): the latest non-terminal offer if one exists (the one-non-terminal-offer-per-project
 * invariant guarantees at most one), otherwise an {@code APPROVED} offer if any (so an agreed offer
 * reads as {@code DONE} even after it is terminal), otherwise the latest terminal offer. All items
 * target the {@code pricing} workspace tab. This contributor is side-effect free (Requirement 1.6).
 */
@Component
public class OfferReadinessContributor implements ProjectReadinessContributor {

    static final String AREA_KEY = "offer";
    static final String NAME_KEY = "workspace.tab.offer";
    static final String TARGET_TAB = "pricing";
    static final String ITEM_ON_APPROVAL = "readiness.offer.onApproval";
    static final String ITEM_NOT_READY = "readiness.offer.notReady";

    private final OfferDao offerDao;
    private final OfferVisibilityResolver offerVisibilityResolver;

    public OfferReadinessContributor(OfferDao offerDao, OfferVisibilityResolver offerVisibilityResolver) {
        this.offerDao = offerDao;
        this.offerVisibilityResolver = offerVisibilityResolver;
    }

    @Override
    public String areaKey() {
        return AREA_KEY;
    }

    @Override
    public String areaResource() {
        return "OFFERS";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public AreaContribution contribute(Long projectId) {
        List<OfferEntity> offers = offerDao.findByProjectIdOrderByIdAsc(projectId);
        OfferStatus currentStatus = currentOffer(offers).map(OfferEntity::getStatus).orElse(null);
        return deriveContribution(currentStatus, offerVisibilityResolver);
    }

    /**
     * The current offer among a project's offers ordered oldest-first: the latest non-terminal offer
     * if any (the one-non-terminal-offer-per-project invariant means at most one), otherwise an
     * {@code APPROVED} offer if any (an agreed offer reads {@code DONE} even once terminal),
     * otherwise the latest offer by id.
     *
     * @param offersOldestFirst the project's offers, oldest first (as the DAO returns them)
     * @return the current offer, or empty when the project has no offer
     */
    static Optional<OfferEntity> currentOffer(List<OfferEntity> offersOldestFirst) {
        if (offersOldestFirst.isEmpty()) {
            return Optional.empty();
        }
        Optional<OfferEntity> latestNonTerminal = latestMatching(
                offersOldestFirst, offer -> !offer.getStatus().isTerminal());
        if (latestNonTerminal.isPresent()) {
            return latestNonTerminal;
        }
        Optional<OfferEntity> latestApproved = latestMatching(
                offersOldestFirst, offer -> offer.getStatus() == OfferStatus.APPROVED);
        if (latestApproved.isPresent()) {
            return latestApproved;
        }
        return Optional.of(offersOldestFirst.get(offersOldestFirst.size() - 1));
    }

    private static Optional<OfferEntity> latestMatching(
            List<OfferEntity> offersOldestFirst, java.util.function.Predicate<OfferEntity> match) {
        OfferEntity latest = null;
        for (OfferEntity offer : offersOldestFirst) {
            if (match.test(offer)) {
                latest = offer;
            }
        }
        return Optional.ofNullable(latest);
    }

    /**
     * The pure derivation of the {@code offer} contribution from the current offer's status
     * (or {@code null} when the project has no offer), via the FOR-05-07 visibility projection.
     * Spring-free so the status → state/percentage mapping is directly unit-testable
     * (FOR-05-11 Requirement 5.3).
     *
     * @param currentStatus the current offer's {@link OfferStatus}, or {@code null} when there is no offer
     * @param resolver      the FOR-05-07 visibility resolver
     * @return the well-formed {@code offer} area contribution
     */
    static AreaContribution deriveContribution(OfferStatus currentStatus, OfferVisibilityResolver resolver) {
        OfferVisibilityStatus visibility =
                currentStatus == null ? null : resolver.visibilityOf(currentStatus);

        if (visibility == OfferVisibilityStatus.APPROVED) {
            return new AreaContribution(AREA_KEY, NAME_KEY, 100, ReadinessState.DONE, List.of());
        }
        if (visibility == OfferVisibilityStatus.ON_APPROVAL) {
            return new AreaContribution(
                    AREA_KEY,
                    NAME_KEY,
                    ReadinessMath.fallbackPercentage(ReadinessState.PARTIAL),
                    ReadinessState.PARTIAL,
                    List.of(new OutstandingItem(ITEM_ON_APPROVAL, Map.of(), TARGET_TAB)));
        }
        // no offer, DRAFT, or terminal REJECTED / WITHDRAWN (CLOSED)
        return new AreaContribution(
                AREA_KEY,
                NAME_KEY,
                0,
                ReadinessState.BLOCKED,
                List.of(new OutstandingItem(ITEM_NOT_READY, Map.of(), TARGET_TAB)));
    }
}
