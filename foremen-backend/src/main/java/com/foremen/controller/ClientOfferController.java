package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.dao.model.OfferEntity;
import com.foremen.service.OfferService;

import lombok.RequiredArgsConstructor;

/**
 * The CLIENT finishing-material selection surface of the Offer stage (FOR-05-07, design
 * §ClientOfferController; Requirements 5.8, 11.1–11.8, 18.5). It exposes the single client-scoped,
 * offer-stage, finishing-Placeholder-only invocation of the FOR-05-05
 * {@code EstimateAssignmentService.chooseConcrete} write path — the write path itself is owned by
 * FOR-05-05; this controller adds only the offer-stage gating and reuses it.
 *
 * <p>This is a <b>distinct</b> controller on the same {@code /api/offers} base path and the same
 * {@code OFFERS} ABAC resource as {@code OfferController}/{@code OfferNegotiationController} (task
 * 9.1); {@link PermissionResource @PermissionResource} is per-class, so several controllers may share
 * the resource. Keeping the client material-selection handler here (rather than growing the offer
 * controller) keeps each controller focused on one concern.
 *
 * <h2>ABAC guarding</h2>
 * The class carries {@link PermissionResource @PermissionResource("OFFERS")}; every handler carries a
 * method-level {@link RequiresPermission @RequiresPermission} that takes precedence over the class
 * default (mirroring {@link EstimateMaterialsController}), so the controller is <b>fully annotated</b>
 * and {@code PermissionAnnotationValidator} classifies it COMPLETE at startup. The
 * choose-concrete write is an {@code (OFFERS, UPDATE)} operation — a CLIENT holds {@code OFFERS}
 * {@code READ + APPROVE} plus this project-scoped material selection; MANAGER/ADMIN hold
 * {@code OFFERS} UPDATE. Project confinement (a CLIENT may act only on their own project's offer,
 * R11.4/R11.5) is enforced by the {@code OFFERS} project scope ({@code offer.project.id}) applied by
 * the ABAC layer; the finishing-Placeholder + non-terminal-negotiable + estimate-DRAFT gate is
 * enforced server-side in {@link OfferService#chooseFinishingConcrete}.
 */
@RestController
@RequestMapping("/api/offers")
@RequiredArgsConstructor
@PermissionResource("OFFERS")
public class ClientOfferController {

    private static final String OFFERS_RESOURCE = "OFFERS";
    private static final String UPDATE_OPERATION = "UPDATE";

    private final OfferService offerService;

    /**
     * Chooses a concrete finishing product for one finishing <b>Placeholder</b> slot of the offer
     * (R5.8, R11.3, R11.4, R11.5, R11.7, R18.5). Delegates to the existing FOR-05-05
     * {@code chooseConcrete} write path through {@link OfferService#chooseFinishingConcrete}, which
     * asserts the offer is non-terminal and negotiable, the target line belongs to the offer's
     * estimate and is a finishing Placeholder (rejecting any other client material write server-side),
     * then recomputes the offer totals over the live-referenced estimate prices (R1.3) and bumps the
     * revision (R11.2). The estimate-{@code DRAFT} half of the gate is enforced by the reused write
     * path's {@code DraftGateGuard}. {@code OFFERS} UPDATE.
     *
     * @param offerId        the offer whose finishing Placeholder is filled
     * @param materialLineId the target finishing material line (must be a Placeholder of the offer's
     *                       estimate)
     * @param request        the chosen concrete finishing product id
     * @return {@code 200} with the updated offer id / revision / totals view
     */
    @PostMapping("/{offerId}/finishing/{materialLineId}/choose-concrete")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<ChooseConcreteResponse> chooseConcrete(
            @PathVariable Long offerId,
            @PathVariable Long materialLineId,
            @RequestBody ChooseConcreteRequest request) {
        OfferEntity offer = offerService.chooseFinishingConcrete(
                offerId, materialLineId, request != null ? request.materialId() : null);
        return ResponseEntity.ok(ChooseConcreteResponse.of(offer));
    }

    /**
     * The choose-concrete write request body: the chosen concrete finishing product id.
     *
     * @param materialId the concrete finishing product to set on the Placeholder line
     */
    public record ChooseConcreteRequest(Long materialId) {
    }

    /**
     * A compact confirmation of the write: the offer id, its bumped revision, and the recomputed
     * totals (offer-level only — no cost/margin/estimate-unit-price field, consistent with the
     * client-confidentiality invariant, R15). The full client read model is served by the
     * {@code OfferController} read endpoints (task 9.1).
     *
     * @param offerId    the affected offer id
     * @param revision   the offer's revision after the write (bumped, R11.2)
     * @param totalNet   the recomputed offer net total (R1.3)
     * @param totalVat   the recomputed offer VAT total (R1.3)
     * @param totalGross the recomputed offer gross total (R1.3)
     */
    public record ChooseConcreteResponse(
            Long offerId,
            Integer revision,
            java.math.BigDecimal totalNet,
            java.math.BigDecimal totalVat,
            java.math.BigDecimal totalGross) {

        /** Maps the updated offer entity to the compact confirmation view. */
        static ChooseConcreteResponse of(OfferEntity offer) {
            return new ChooseConcreteResponse(
                    offer.getId(),
                    offer.getRevision(),
                    offer.getTotalNet(),
                    offer.getTotalVat(),
                    offer.getTotalGross());
        }
    }
}
