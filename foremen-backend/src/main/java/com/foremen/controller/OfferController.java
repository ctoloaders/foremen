package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.dao.model.OfferEntity;
import com.foremen.service.OfferService;
import com.foremen.service.offer.ClientOfferReadModel;
import com.foremen.service.offer.ClientOfferReadModelAssembler;
import com.foremen.service.offer.ExecutorOfferReadModel;
import com.foremen.service.offer.OfferVisibilityResolver;

import lombok.RequiredArgsConstructor;

/**
 * The Offer lifecycle controller (FOR-05-07, design §Controllers) — offer preparation, package
 * selection, the send/withdraw/approve/reject transitions, and the audience-branched offer read,
 * layered on the new {@code OFFERS} ABAC resource (seeded by changeset 133 with the custom
 * {@code APPROVE} operation).
 *
 * <h2>ABAC guarding (R5.1, R5.2, R5.4, R5.6)</h2>
 * The class carries {@link PermissionResource @PermissionResource("OFFERS")}; every handler carries a
 * method-level {@link RequiresPermission @RequiresPermission} that takes precedence over the class
 * default (mirroring {@link EstimateMatrixController} / {@link EstimateMaterialsController}), so the
 * controller is <b>fully annotated</b> and {@code PermissionAnnotationValidator} classifies it
 * COMPLETE at startup:
 * <ul>
 *   <li>{@code POST /project/{projectId}/prepare} — build an offer from a PRICED estimate,
 *       {@code OFFERS} CREATE (executor action, R5.4).</li>
 *   <li>{@code POST /{offerId}/select-package} — MANAGER/ADMIN package selection, {@code OFFERS}
 *       UPDATE.</li>
 *   <li>{@code POST /{offerId}/send} — send a DRAFT offer to the client, {@code OFFERS} UPDATE
 *       (R5.4).</li>
 *   <li>{@code POST /{offerId}/withdraw} — executor withdraws a non-terminal offer, {@code OFFERS}
 *       UPDATE.</li>
 *   <li>{@code POST /{offerId}/approve}, {@code POST /{offerId}/reject} — the CLIENT decision
 *       sub-actions, guarded by the first-class {@code (OFFERS, APPROVE)} grant (R5.4/R5.6).</li>
 *   <li>{@code GET /{offerId}} — the offer read, {@code OFFERS} READ. CLIENT reads are gated on
 *       visibility ({@link OfferVisibilityResolver#assertClientVisible}) and served the confidential
 *       {@link ClientOfferReadModel}; MANAGER/ADMIN reads are served the {@link ExecutorOfferReadModel}
 *       (R5.9, R15.1, R15.4, R15.5, R17.5).</li>
 * </ul>
 *
 * <p>Reads branch on the caller role resolved from the security context (mirroring
 * {@code EstimateMatrixController#currentRoleCode}): a CLIENT is served ONLY the
 * {@link ClientOfferReadModel} — the single confidentiality boundary that never carries a
 * cost/margin/worker-rate/estimate-unit-price field — after the visibility gate hides a not-yet-sent
 * (DRAFT-visibility) offer as {@code 404 error.entity.not.found}; every other role is served the
 * {@link ExecutorOfferReadModel}.
 */
@RestController
@RequestMapping("/api/offers")
@RequiredArgsConstructor
@PermissionResource("OFFERS")
public class OfferController {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String OFFERS_RESOURCE = "OFFERS";
    private static final String CREATE_OPERATION = "CREATE";
    private static final String READ_OPERATION = "READ";
    private static final String UPDATE_OPERATION = "UPDATE";
    private static final String APPROVE_OPERATION = "APPROVE";
    private static final String CLIENT_ROLE = "CLIENT";

    private final OfferService offerService;
    private final ClientOfferReadModelAssembler clientOfferReadModelAssembler;
    private final OfferVisibilityResolver offerVisibilityResolver;

    /**
     * Prepares a new offer for the project from its PRICED estimate (R1.1/R1.2, R5.4). Executor
     * action — {@code OFFERS} CREATE. Returns the executor read model of the freshly-created DRAFT
     * offer.
     */
    @PostMapping("/project/{projectId}/prepare")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = CREATE_OPERATION)
    public ResponseEntity<ExecutorOfferReadModel> prepare(@PathVariable Long projectId) {
        OfferEntity offer = offerService.prepareOffer(projectId);
        return ResponseEntity.ok(clientOfferReadModelAssembler.toExecutorReadModel(offer, true));
    }

    /**
     * Selects the offer's package and re-derives the finishing selection / totals (R1.5, R5.4).
     * Executor action — {@code OFFERS} UPDATE. The DRAFT-lifecycle service lock and the
     * terminal-immutability guard are enforced in {@link OfferService#selectPackage}.
     */
    @PostMapping("/{offerId}/select-package")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<ExecutorOfferReadModel> selectPackage(
            @PathVariable Long offerId, @RequestBody SelectPackageRequest request) {
        OfferEntity offer = offerService.selectPackage(offerId, request != null ? request.packageCode() : null);
        return ResponseEntity.ok(clientOfferReadModelAssembler.toExecutorReadModel(offer, true));
    }

    /**
     * Sends a DRAFT offer to the client — {@code DRAFT → SENT}, project {@code READY_TO_OFFER →
     * OFFERED}, visibility {@code ON_APPROVAL} (R3.2, R5.4). Executor action — {@code OFFERS} UPDATE.
     */
    @PostMapping("/{offerId}/send")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<ExecutorOfferReadModel> send(@PathVariable Long offerId) {
        OfferEntity offer = offerService.send(offerId);
        return ResponseEntity.ok(clientOfferReadModelAssembler.toExecutorReadModel(offer, false));
    }

    /**
     * Withdraws a non-terminal offer ({@code → WITHDRAWN}, R3.7). Executor action — {@code OFFERS}
     * UPDATE.
     */
    @PostMapping("/{offerId}/withdraw")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<ExecutorOfferReadModel> withdraw(@PathVariable Long offerId) {
        OfferEntity offer = offerService.withdraw(offerId);
        return ResponseEntity.ok(clientOfferReadModelAssembler.toExecutorReadModel(offer, false));
    }

    /**
     * The CLIENT approve decision — {@code SENT}/{@code COUNTERED → APPROVED}, project {@code OFFERED →
     * APPROVED} (R3.5, R5.6). Guarded by the first-class {@code (OFFERS, APPROVE)} grant. Returns the
     * confidential client read model of the approved offer.
     */
    @PostMapping("/{offerId}/approve")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = APPROVE_OPERATION)
    public ResponseEntity<ClientOfferReadModel> approve(@PathVariable Long offerId) {
        OfferEntity offer = offerService.approve(offerId);
        return ResponseEntity.ok(clientOfferReadModelAssembler.toClientReadModel(offer));
    }

    /**
     * The CLIENT reject decision — {@code SENT}/{@code COUNTERED → REJECTED} (R3.6, R5.6). Guarded by
     * the first-class {@code (OFFERS, APPROVE)} grant (the approve/reject decision is one client
     * sub-action). Returns the confidential client read model of the rejected offer.
     */
    @PostMapping("/{offerId}/reject")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = APPROVE_OPERATION)
    public ResponseEntity<ClientOfferReadModel> reject(@PathVariable Long offerId) {
        OfferEntity offer = offerService.reject(offerId);
        return ResponseEntity.ok(clientOfferReadModelAssembler.toClientReadModel(offer));
    }

    /**
     * Reads the offer, branched by audience (R5.9, R15.1, R15.4, R15.5, R17.5): a CLIENT is served the
     * confidential {@link ClientOfferReadModel} after the visibility gate (a DRAFT-visibility offer is
     * hidden as {@code 404 error.entity.not.found}); a MANAGER/ADMIN is served the
     * {@link ExecutorOfferReadModel}. {@code OFFERS} READ.
     */
    @GetMapping("/{offerId}")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<?> getOffer(@PathVariable Long offerId) {
        OfferEntity offer = offerService.getOffer(offerId);
        if (isClient()) {
            // R5.9/R17.5: a not-yet-sent (DRAFT-visibility) offer is indistinguishable from missing.
            offerVisibilityResolver.assertClientVisible(offer.getStatus(), offerId);
            return ResponseEntity.ok(clientOfferReadModelAssembler.toClientReadModel(offer));
        }
        return ResponseEntity.ok(clientOfferReadModelAssembler.toExecutorReadModel(offer, true));
    }

    /** Whether the authenticated caller's role is CLIENT (selects the confidential read model). */
    private boolean isClient() {
        return CLIENT_ROLE.equals(currentRoleCode());
    }

    /**
     * The authenticated caller's role code, read from the {@code ROLE_<code>} authority in the
     * security context, or {@code null} when there is no authenticated principal. Mirrors the
     * extraction used by {@link EstimateMatrixController} / {@code PermissionInterceptor}.
     */
    private String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith(ROLE_PREFIX)) {
                return authority.substring(ROLE_PREFIX.length());
            }
        }
        return null;
    }

    /** Request body for {@code POST /{offerId}/select-package}: the target package code. */
    public record SelectPackageRequest(String packageCode) {
    }
}
