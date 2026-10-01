package com.foremen.service.offer;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.OfferDiscountEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferNegotiationRoundEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.OfferVisibilityStatus;

/**
 * Assembles the offer read models served to callers (FOR-05-07, Requirements 7.4, 15.1, 15.2,
 * 19.3). It is the <b>only</b> path that turns an {@link OfferEntity} into a client-facing payload,
 * and by construction it maps ONLY offer-level fields into the {@link ClientOfferReadModel} graph —
 * it never reads or maps a self-cost, cost, margin, worker rate, or estimate-internal unit price, so
 * the client type graph is structurally free of confidential fields (Property 21).
 *
 * <p><b>Skeleton (task 2.3).</b> This wires the DTO/assembler graph so it compiles and the offer-level
 * fields available directly on the entity (status, derived visibility, revision, selected-package
 * code, the derived totals cache, applied discounts, the negotiation thread) are mapped. The
 * price-derivation collaborators — the live-referenced estimate client-facing prices feeding
 * {@link PerLineOfferPrice}/{@link PerCategoryOfferPrice}/{@link PackagePriceView}, the finishing
 * selection surface, and the {@link OfferReadinessView} — are populated in the controller/service
 * tasks (9.x) that own the estimate read model and the readiness calculator. Those are left as empty
 * lists / {@code null} here (never as confidential data) so the confidentiality boundary holds even
 * in the skeleton.
 *
 * <p>The assembler is a stateless {@code @Component} mirroring the sibling read-model assembler
 * convention ({@code MaterialsListAssembler}, {@code MarginsListAssembler}).
 */
@Component
public class ClientOfferReadModelAssembler {

    private final OfferVisibilityResolver visibilityResolver;

    public ClientOfferReadModelAssembler(OfferVisibilityResolver visibilityResolver) {
        this.visibilityResolver = visibilityResolver;
    }

    /**
     * Builds the confidential client-facing read model for {@code offer}. Maps only offer-level
     * fields; every price shown is an offer-facing client price. The estimate is referenced by id
     * only (R19.3).
     *
     * <p>Skeleton: the price-projection collections and readiness are populated by task 9.x; here
     * they are empty/{@code null} placeholders (never confidential data).
     *
     * @param offer the offer aggregate; must be non-null
     * @return the assembled client read model
     */
    public ClientOfferReadModel toClientReadModel(OfferEntity offer) {
        OfferVisibilityStatus visibility = visibilityResolver.visibilityOf(offer.getStatus());
        return new ClientOfferReadModel(
                offer.getId(),
                offer.getEstimate() != null ? offer.getEstimate().getId() : null,
                offer.getProject() != null ? offer.getProject().getId() : null,
                offer.getStatus(),
                visibility,
                offer.getRevision(),
                selectedPackageCode(offer),
                offer.getTotalNet(),
                offer.getTotalVat(),
                offer.getTotalGross(),
                // Populated in task 9.x from the live-referenced estimate client-facing prices.
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                appliedDiscounts(offer),
                // Finishing selection surface populated in task 9.x (reuses FOR-05-05).
                new ArrayList<>(),
                negotiationThread(offer),
                // Readiness populated in task 9.x via OfferReadinessCalculator.
                null);
    }

    /**
     * Builds the MANAGER/ADMIN-facing read model: the same offer projection the client sees plus the
     * manager control-state block. The kosztorys cost/margin figures are NOT folded in — per R16
     * they remain their own MANAGER/ADMIN-only read models.
     *
     * <p>Skeleton: the {@link ManagerControlState} is a placeholder wired to the entity's obvious
     * flags; escalation caps and pending-approval resolution are populated in task 9.x.
     *
     * @param offer    the offer aggregate; must be non-null
     * @param editable whether the caller may currently write the offer
     * @return the assembled executor read model
     */
    public ExecutorOfferReadModel toExecutorReadModel(OfferEntity offer, boolean editable) {
        ClientOfferReadModel clientView = toClientReadModel(offer);
        ManagerControlState control = new ManagerControlState(
                editable,
                false,
                null,
                null,
                openRoundCount(offer));
        return new ExecutorOfferReadModel(clientView, control);
    }

    /**
     * Builds the downstream {@link AgreedOfferView} of an approved offer (R7.4). Captures the agreed
     * revision's totals, selected package, and applied discounts.
     *
     * @param offer the approved offer aggregate; must be non-null
     * @return the agreed-version view
     */
    public AgreedOfferView toAgreedView(OfferEntity offer) {
        return new AgreedOfferView(
                offer.getId(),
                offer.getEstimate() != null ? offer.getEstimate().getId() : null,
                offer.getApprovedRevision(),
                selectedPackageCode(offer),
                offer.getTotalNet(),
                offer.getTotalVat(),
                offer.getTotalGross(),
                appliedDiscounts(offer));
    }

    private static String selectedPackageCode(OfferEntity offer) {
        OfferPackageEntity pkg = offer.getSelectedPackage();
        return pkg != null ? pkg.getCode() : null;
    }

    /** Maps the offer's applied discounts to their client-facing view (offer-level facts only). */
    private static List<AppliedDiscountView> appliedDiscounts(OfferEntity offer) {
        List<AppliedDiscountView> views = new ArrayList<>();
        if (offer.getDiscounts() != null) {
            for (OfferDiscountEntity discount : offer.getDiscounts()) {
                views.add(new AppliedDiscountView(
                        discount.getScope(),
                        discount.getKind(),
                        discount.getValue(),
                        discount.getTargetId(),
                        // Effective amount is resolved by DiscountResolver/OfferTotalsCalculator in task 9.x.
                        null));
            }
        }
        return views;
    }

    /** Maps the ordered negotiation thread to its client-facing view (offer-level facts only). */
    private static List<NegotiationRoundView> negotiationThread(OfferEntity offer) {
        List<NegotiationRoundView> views = new ArrayList<>();
        if (offer.getNegotiationRounds() != null) {
            for (OfferNegotiationRoundEntity round : offer.getNegotiationRounds()) {
                views.add(new NegotiationRoundView(
                        round.getRoundNo(),
                        round.getOfferRevision(),
                        round.getInitiatorRole(),
                        round.getKind(),
                        round.getStatus(),
                        round.getScope(),
                        round.getTargetId(),
                        round.getValueKind(),
                        round.getValue(),
                        round.getJustification(),
                        round.getExplanation(),
                        round.getClientComment(),
                        round.getCreatedDate()));
            }
        }
        return views;
    }

    private static int openRoundCount(OfferEntity offer) {
        if (offer.getNegotiationRounds() == null) {
            return 0;
        }
        return (int) offer.getNegotiationRounds().stream()
                .filter(r -> r.getStatus() == com.foremen.dao.model.NegotiationRoundStatus.OPEN)
                .count();
    }
}
