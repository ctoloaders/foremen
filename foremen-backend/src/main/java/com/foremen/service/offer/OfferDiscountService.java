package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferDiscountDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.OfferDiscountEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;

import jakarta.persistence.EntityManager;

/**
 * Write / validation service for {@link OfferDiscountEntity} — the scoped discount/rebate lines
 * applied on top of an offer's live-referenced estimate prices (FOR-05-07, Requirements 2.2, 2.3,
 * 2.4, 2.6, 2.8, 3.9, 5.3; design §OfferDiscountService).
 *
 * <p>Every discount write ({@link #add}, {@link #edit}, {@link #remove}) routes through the shared
 * {@link #write(OfferEntity, OfferDiscountEntity, DiscountWrite)} core, which enforces, in order:
 *
 * <ol>
 *   <li><b>Executor-only writer</b> (R2.8 / R5.3) — discounts are MANAGER/ADMIN-controlled writes;
 *       a non-executor caller is rejected server-side with {@code 403
 *       error.offer.discount.forbidden}. The acting role is resolved from the security context the
 *       same way {@link OfferService} does (ADMIN via authorities, otherwise the user's role code by
 *       numeric principal id) and checked against {@link OfferStatusMachine#executorRoles()}.</li>
 *   <li><b>Terminal-offer guard</b> (R3.9) — a write on a terminal offer
 *       ({@code APPROVED}/{@code REJECTED}/{@code WITHDRAWN}) is rejected with {@code 409
 *       error.offer.illegal.transition} by reusing the shared {@link OfferService#assertNonTerminal}
 *       guard, so the terminal-immutability invariant holds uniformly across every offer write
 *       path.</li>
 *   <li><b>Value validation</b> — {@code value >= 0} (R2.2 → {@code error.offer.discount.negative}),
 *       a {@code PERCENT} value {@code <= 100} (R2.3 →
 *       {@code error.offer.discount.percent.over.100}), and an {@code ABSOLUTE} value not exceeding
 *       the net subtotal of its scope base (R2.4 → {@code error.offer.discount.absolute.over.base}).
 *       The scope base is the whole estimate net for {@code GLOBAL}, the category subtotal for
 *       {@code CATEGORY}, and the line net for {@code LINE}, computed live from the referenced
 *       estimate's lines.</li>
 * </ol>
 *
 * <p>After a validated add/edit/remove the offer totals are recomputed from the live estimate
 * prices, the surviving effective discounts (resolved by {@link DiscountResolver}), and the project
 * VAT rate by reusing the shared {@link OfferService#recomputeTotals(OfferEntity)} helper (R2.6), so
 * the discount and totals recompute stay consistent with the rest of the offer lifecycle rather than
 * being duplicated here.
 */
@Service
public class OfferDiscountService {

    /** 403 when a discount write is attempted by a non-executor (not MANAGER/ADMIN) (R2.8 / R5.3). */
    static final String FORBIDDEN_WRITER_MESSAGE = "error.offer.discount.forbidden";

    /** 400 when a discount value is negative (R2.2). */
    static final String NEGATIVE_VALUE_MESSAGE = "error.offer.discount.negative";

    /** 400 when a {@code PERCENT} discount value exceeds 100 (R2.3). */
    static final String PERCENT_OVER_100_MESSAGE = "error.offer.discount.percent.over.100";

    /** 400 when an {@code ABSOLUTE} discount value exceeds its scope base (R2.4). */
    static final String ABSOLUTE_OVER_BASE_MESSAGE = "error.offer.discount.absolute.over.base";

    /** 404 when the referenced offer or discount cannot be resolved. */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final OfferDao offerDao;
    private final OfferDiscountDao offerDiscountDao;
    private final UserDao userDao;
    private final OfferService offerService;
    private final EntityManager entityManager;

    public OfferDiscountService(OfferDao offerDao,
                                OfferDiscountDao offerDiscountDao,
                                UserDao userDao,
                                OfferService offerService,
                                EntityManager entityManager) {
        this.offerDao = offerDao;
        this.offerDiscountDao = offerDiscountDao;
        this.userDao = userDao;
        this.offerService = offerService;
        this.entityManager = entityManager;
    }

    /**
     * The write payload for a discount: its scope, target, kind, and value (decoupled from the
     * entity so a controller passes a plain value object).
     *
     * @param scope    {@code GLOBAL} / {@code CATEGORY} / {@code LINE}
     * @param targetId {@code null} for {@code GLOBAL}; the work-category id for {@code CATEGORY}; the
     *                 estimate-line id for {@code LINE}
     * @param kind     {@code PERCENT} / {@code ABSOLUTE}
     * @param value    the discount magnitude (a percentage for {@code PERCENT}, a money amount for
     *                 {@code ABSOLUTE})
     */
    public record DiscountWrite(DiscountScope scope, Long targetId, DiscountKind kind, BigDecimal value) {
    }

    /**
     * Adds a new discount to {@code offerId} after enforcing the writer role, terminal-offer, and
     * value validations, then recomputes the offer totals (R2.6).
     *
     * @param offerId the offer to add the discount to
     * @param request the discount payload
     * @return the persisted discount entity
     * @throws ForemenApiException 403 non-executor writer; 409 terminal offer; 400 invalid value;
     *                             404 offer missing
     */
    @Transactional
    public OfferDiscountEntity add(Long offerId, DiscountWrite request) {
        OfferEntity offer = resolveOffer(offerId);
        OfferDiscountEntity discount = new OfferDiscountEntity();
        discount.setOffer(offer);
        return write(offer, discount, request);
    }

    /**
     * Edits an existing discount of {@code offerId} after enforcing the writer role, terminal-offer,
     * and value validations, then recomputes the offer totals (R2.6).
     *
     * @param offerId    the owning offer
     * @param discountId the discount to edit
     * @param request    the new discount payload
     * @return the updated discount entity
     * @throws ForemenApiException 403 non-executor writer; 409 terminal offer; 400 invalid value;
     *                             404 offer/discount missing
     */
    @Transactional
    public OfferDiscountEntity edit(Long offerId, Long discountId, DiscountWrite request) {
        OfferEntity offer = resolveOffer(offerId);
        OfferDiscountEntity discount = resolveDiscount(offer, discountId);
        return write(offer, discount, request);
    }

    /**
     * Removes a discount from {@code offerId} after enforcing the writer role and terminal-offer
     * guard, then recomputes the offer totals (R2.6). Removal carries no value validation.
     *
     * @param offerId    the owning offer
     * @param discountId the discount to remove
     * @throws ForemenApiException 403 non-executor writer; 409 terminal offer; 404 offer/discount
     *                             missing
     */
    @Transactional
    public void remove(Long offerId, Long discountId) {
        OfferEntity offer = resolveOffer(offerId);
        OfferDiscountEntity discount = resolveDiscount(offer, discountId);

        assertExecutorWriter();
        offerService.assertNonTerminal(offer);

        offer.getDiscounts().remove(discount);
        offerDiscountDao.delete(discount);

        offerService.recomputeTotals(offer);
        offerDao.save(offer);
        entityManager.flush();
    }

    /**
     * The shared discount write core (add/edit): enforces the executor-only writer (R2.8 / R5.3),
     * the terminal-offer guard (R3.9), and the value validations (R2.2 / R2.3 / R2.4), applies the
     * payload to the {@code discount}, persists it, and recomputes the offer totals (R2.6).
     *
     * @param offer    the owning offer (already resolved)
     * @param discount the discount being written (new for add, managed for edit)
     * @param request  the discount payload
     * @return the persisted discount entity
     */
    @Transactional
    public OfferDiscountEntity write(OfferEntity offer, OfferDiscountEntity discount, DiscountWrite request) {
        // R2.8 / R5.3: only MANAGER/ADMIN may write discounts.
        assertExecutorWriter();
        // R3.9: no write on a terminal offer (shared guard, indistinguishable from an illegal transition).
        offerService.assertNonTerminal(offer);
        // R2.2 / R2.3 / R2.4: value validations against the scope base.
        validate(offer, request);

        discount.setScope(request.scope());
        discount.setTargetId(request.targetId());
        discount.setKind(request.kind());
        discount.setValue(request.value());

        OfferDiscountEntity saved = offerDiscountDao.save(discount);
        if (!offer.getDiscounts().contains(saved)) {
            offer.getDiscounts().add(saved);
        }

        // R2.6: recompute the offer totals from the live estimate prices + effective discounts.
        offerService.recomputeTotals(offer);
        offerDao.save(offer);
        entityManager.flush();
        return saved;
    }

    // --- validation ---

    /**
     * Validates a discount payload against Requirement 2: non-negative value (R2.2), a percent value
     * within {@code [0, 100]} (R2.3), and an absolute value not exceeding its scope base (R2.4).
     */
    private void validate(OfferEntity offer, DiscountWrite request) {
        BigDecimal value = request.value();

        // R2.2: reject a negative (or null) value.
        if (value == null || value.signum() < 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, NEGATIVE_VALUE_MESSAGE);
        }

        if (request.kind() == DiscountKind.PERCENT) {
            // R2.3: a percent discount may not exceed 100.
            if (value.compareTo(HUNDRED) > 0) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, PERCENT_OVER_100_MESSAGE);
            }
        } else if (request.kind() == DiscountKind.ABSOLUTE) {
            // R2.4: an absolute discount may not exceed the net subtotal of its scope base.
            BigDecimal scopeBase = scopeBase(offer, request.scope(), request.targetId());
            if (value.compareTo(scopeBase) > 0) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, ABSOLUTE_OVER_BASE_MESSAGE);
            }
        }
    }

    /**
     * The net subtotal of a discount's scope base, computed live from the offer's referenced estimate
     * lines (R2.4): the whole estimate net for {@code GLOBAL}, the category subtotal (sum of the
     * lines whose work-category equals {@code targetId}) for {@code CATEGORY}, and the line net for
     * {@code LINE}. A target that matches no line yields a zero base (so any positive absolute value
     * is rejected).
     */
    private BigDecimal scopeBase(OfferEntity offer, DiscountScope scope, Long targetId) {
        List<EstimateLineEntity> lines = estimateLines(offer);
        BigDecimal base = BigDecimal.ZERO;
        for (EstimateLineEntity line : lines) {
            if (line == null) {
                continue;
            }
            BigDecimal net = normalizeNet(line.getValueNet());
            switch (scope) {
                case GLOBAL -> base = base.add(net);
                case CATEGORY -> {
                    if (targetId != null && targetId.equals(categoryIdOf(line))) {
                        base = base.add(net);
                    }
                }
                case LINE -> {
                    if (targetId != null && targetId.equals(line.getId())) {
                        base = base.add(net);
                    }
                }
            }
        }
        return base;
    }

    private List<EstimateLineEntity> estimateLines(OfferEntity offer) {
        EstimateEntity estimate = offer.getEstimate();
        if (estimate == null || estimate.getLines() == null) {
            return List.of();
        }
        return estimate.getLines();
    }

    /** The work-category (work-type group) id of a line, or {@code null} when unresolved. */
    private static Long categoryIdOf(EstimateLineEntity line) {
        if (line.getWorkItem() == null || line.getWorkItem().getWorkCategory() == null) {
            return null;
        }
        return line.getWorkItem().getWorkCategory().getId();
    }

    private static BigDecimal normalizeNet(BigDecimal net) {
        return net != null && net.signum() > 0 ? net : BigDecimal.ZERO;
    }

    // --- resolution helpers ---

    private OfferEntity resolveOffer(Long offerId) {
        return offerDao.findById(offerId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "offerId", offerId));
    }

    private OfferDiscountEntity resolveDiscount(OfferEntity offer, Long discountId) {
        return offer.getDiscounts().stream()
                .filter(d -> d.getId() != null && d.getId().equals(discountId))
                .findFirst()
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "discountId", discountId));
    }

    // --- writer-role resolution (mirrors OfferService) ---

    /**
     * Asserts the acting caller is an executor (MANAGER/ADMIN); otherwise rejects the write with
     * {@code 403 error.offer.discount.forbidden} (R2.8 / R5.3). The role is resolved from the
     * security context the same way {@link OfferService} resolves its actor role.
     */
    private void assertExecutorWriter() {
        String role = resolveActorRole();
        if (role == null || !OfferStatusMachine.executorRoles().contains(role)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, FORBIDDEN_WRITER_MESSAGE);
        }
    }

    /**
     * Resolves the acting caller's role code: {@code "ADMIN"} when the authentication carries the
     * ADMIN authority, otherwise the role code of the user identified by the numeric principal name;
     * {@code null} when no role can be resolved.
     */
    private String resolveActorRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if ("ROLE_ADMIN".equals(a) || "ADMIN".equals(a)) {
                return "ADMIN";
            }
        }
        Long userId = parseUserId(auth.getName());
        if (userId == null) {
            return null;
        }
        UserEntity user = userDao.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        RoleEntity role = user.getRole();
        return role != null ? role.getCode() : null;
    }

    private static Long parseUserId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
