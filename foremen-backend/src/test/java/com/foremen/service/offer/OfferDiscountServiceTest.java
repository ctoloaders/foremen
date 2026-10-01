package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferDiscountDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.OfferDiscountEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;
import com.foremen.service.offer.OfferDiscountService.DiscountWrite;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OfferDiscountService} (FOR-05-07-offer-approval, task 6.2), exercising the
 * shared discount write core with stubbed DAOs, a mocked {@link OfferService} (whose
 * {@code assertNonTerminal} / {@code recomputeTotals} collaborators the service delegates to), a
 * mocked {@link EntityManager}, and a manually-driven {@link SecurityContextHolder} — following the
 * {@code EstimateServiceTest} / {@code ProjectMemberServiceTest} Mockito convention for a service
 * with DAO collaborators (no Spring context).
 *
 * <p>The security context is set to an executor (MANAGER via the user's role code, or ADMIN via a
 * granted authority) for the happy-path and value-validation cases, and to a non-executor (CLIENT)
 * for the forbidden-writer case, mirroring how {@code OfferDiscountService} resolves the acting role
 * exactly like {@code OfferService} (ADMIN by authority, else the role code of the numeric principal
 * id). The context is cleared per test so the suite re-runs cleanly.
 *
 * <p>The offer is built with an estimate whose lines give a deterministic scope base
 * ({@code GLOBAL} net = 300, the {@code LINE} 501 net = 100), so the {@code ABSOLUTE}-over-base
 * validation is exact. A terminal ({@code APPROVED}) offer drives the terminal-write rejection
 * (via the shared {@code OfferService.assertNonTerminal} guard). Because the totals recompute is
 * delegated to {@code OfferService.recomputeTotals}, "totals recomputed on every change" is asserted
 * by verifying {@code recomputeTotals(offer)} is invoked on add / edit / remove.
 *
 * <p><b>Validates: Requirements 2.2, 2.3, 2.4, 2.6, 2.8, 3.9, 5.3</b>
 */
@ExtendWith(MockitoExtension.class)
class OfferDiscountServiceTest {

    @Mock
    private OfferDao offerDao;
    @Mock
    private OfferDiscountDao offerDiscountDao;
    @Mock
    private UserDao userDao;
    @Mock
    private OfferService offerService;
    @Mock
    private EntityManager entityManager;

    private OfferDiscountService service;

    private static final Long OFFER_ID = 700L;
    private static final Long DISCOUNT_ID = 900L;
    private static final Long MANAGER_USER_ID = 11L;
    private static final Long CLIENT_USER_ID = 22L;

    // Deterministic scope base: line 501 net = 100 (category 1), line 502 net = 200 (category 2).
    // GLOBAL base = 300; CATEGORY(1) base = 100; LINE(501) base = 100.
    private static final Long LINE_501_ID = 501L;
    private static final Long CATEGORY_1_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new OfferDiscountService(offerDao, offerDiscountDao, userDao, offerService, entityManager);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------------------------------
    // R2.2 — negative value rejected with error.offer.discount.negative
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("add: negative value rejected 400 error.offer.discount.negative (R2.2)")
    void negativeValueRejected() {
        authenticateManager();
        OfferEntity offer = draftOffer();
        when(offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));

        DiscountWrite request = new DiscountWrite(DiscountScope.GLOBAL, null, DiscountKind.ABSOLUTE,
                new BigDecimal("-1"));

        assertApiError(() -> service.add(OFFER_ID, request), HttpStatus.BAD_REQUEST,
                "error.offer.discount.negative");

        // Rejected before any persistence or totals recompute side effect.
        verify(offerDiscountDao, never()).save(any(OfferDiscountEntity.class));
        verify(offerService, never()).recomputeTotals(any(OfferEntity.class));
    }

    // ------------------------------------------------------------------------------------------
    // R2.3 — PERCENT value over 100 rejected with error.offer.discount.percent.over.100
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("add: PERCENT value > 100 rejected 400 error.offer.discount.percent.over.100 (R2.3)")
    void percentOver100Rejected() {
        authenticateManager();
        OfferEntity offer = draftOffer();
        when(offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));

        DiscountWrite request = new DiscountWrite(DiscountScope.GLOBAL, null, DiscountKind.PERCENT,
                new BigDecimal("100.01"));

        assertApiError(() -> service.add(OFFER_ID, request), HttpStatus.BAD_REQUEST,
                "error.offer.discount.percent.over.100");

        verify(offerDiscountDao, never()).save(any(OfferDiscountEntity.class));
        verify(offerService, never()).recomputeTotals(any(OfferEntity.class));
    }

    // ------------------------------------------------------------------------------------------
    // R2.4 — ABSOLUTE value over the scope base rejected with error.offer.discount.absolute.over.base
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("add: ABSOLUTE value > scope base rejected 400 error.offer.discount.absolute.over.base (R2.4)")
    void absoluteOverBaseRejected() {
        authenticateManager();
        OfferEntity offer = draftOffer();
        when(offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));

        // LINE 501 net base = 100; an absolute discount of 150 exceeds it.
        DiscountWrite request = new DiscountWrite(DiscountScope.LINE, LINE_501_ID, DiscountKind.ABSOLUTE,
                new BigDecimal("150"));

        assertApiError(() -> service.add(OFFER_ID, request), HttpStatus.BAD_REQUEST,
                "error.offer.discount.absolute.over.base");

        verify(offerDiscountDao, never()).save(any(OfferDiscountEntity.class));
        verify(offerService, never()).recomputeTotals(any(OfferEntity.class));
    }

    @Test
    @DisplayName("add: ABSOLUTE value equal to the scope base is accepted (R2.4 boundary)")
    void absoluteAtBaseAccepted() {
        authenticateManager();
        OfferEntity offer = draftOffer();
        when(offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));
        when(offerDiscountDao.save(any(OfferDiscountEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // LINE 501 net base = 100; an absolute discount of exactly 100 sits on the boundary → allowed.
        DiscountWrite request = new DiscountWrite(DiscountScope.LINE, LINE_501_ID, DiscountKind.ABSOLUTE,
                new BigDecimal("100"));

        OfferDiscountEntity saved = service.add(OFFER_ID, request);

        assertThat(saved.getValue()).isEqualByComparingTo("100");
        assertThat(saved.getScope()).isEqualTo(DiscountScope.LINE);
        verify(offerService, times(1)).recomputeTotals(offer);
    }

    // ------------------------------------------------------------------------------------------
    // R2.8 / R5.3 — non-executor writer rejected server-side with 403 error.offer.discount.forbidden
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("add: non-executor (CLIENT) writer rejected 403 error.offer.discount.forbidden (R2.8/R5.3)")
    void nonExecutorWriterRejected() {
        authenticateClient();
        OfferEntity offer = draftOffer();
        when(offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));

        DiscountWrite request = new DiscountWrite(DiscountScope.GLOBAL, null, DiscountKind.PERCENT,
                new BigDecimal("10"));

        assertApiError(() -> service.add(OFFER_ID, request), HttpStatus.FORBIDDEN,
                "error.offer.discount.forbidden");

        // Rejected before any validation, persistence, or totals recompute.
        verify(offerDiscountDao, never()).save(any(OfferDiscountEntity.class));
        verify(offerService, never()).recomputeTotals(any(OfferEntity.class));
        verify(offerService, never()).assertNonTerminal(any(OfferEntity.class));
    }

    // ------------------------------------------------------------------------------------------
    // R3.9 — write on a terminal (APPROVED) offer rejected with 409 error.offer.illegal.transition
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("add: write on a terminal (APPROVED) offer rejected 409 error.offer.illegal.transition (R3.9)")
    void terminalOfferWriteRejected() {
        authenticateManager();
        OfferEntity offer = draftOffer();
        offer.setStatus(OfferStatus.APPROVED);
        when(offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));
        // The shared terminal guard rejects a write on a terminal offer (mirrors OfferService).
        doThrow(new ForemenApiException(HttpStatus.CONFLICT, "error.offer.illegal.transition"))
                .when(offerService).assertNonTerminal(offer);

        DiscountWrite request = new DiscountWrite(DiscountScope.GLOBAL, null, DiscountKind.PERCENT,
                new BigDecimal("10"));

        assertApiError(() -> service.add(OFFER_ID, request), HttpStatus.CONFLICT,
                "error.offer.illegal.transition");

        // Rejected before persistence or totals recompute.
        verify(offerDiscountDao, never()).save(any(OfferDiscountEntity.class));
        verify(offerService, never()).recomputeTotals(any(OfferEntity.class));
    }

    // ------------------------------------------------------------------------------------------
    // R2.6 — totals recomputed on add / edit / remove
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("add: totals recomputed on a valid add (R2.6)")
    void totalsRecomputedOnAdd() {
        authenticateManager();
        OfferEntity offer = draftOffer();
        when(offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));
        when(offerDiscountDao.save(any(OfferDiscountEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DiscountWrite request = new DiscountWrite(DiscountScope.GLOBAL, null, DiscountKind.PERCENT,
                new BigDecimal("10"));

        service.add(OFFER_ID, request);

        verify(offerService, times(1)).recomputeTotals(offer);
        verify(offerDao, times(1)).save(offer);
    }

    @Test
    @DisplayName("edit: totals recomputed on a valid edit (R2.6)")
    void totalsRecomputedOnEdit() {
        authenticateManager();
        OfferEntity offer = draftOffer();
        OfferDiscountEntity existing = existingDiscount(offer);
        when(offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));
        when(offerDiscountDao.save(any(OfferDiscountEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DiscountWrite request = new DiscountWrite(DiscountScope.GLOBAL, null, DiscountKind.PERCENT,
                new BigDecimal("20"));

        OfferDiscountEntity result = service.edit(OFFER_ID, DISCOUNT_ID, request);

        // The managed discount is updated in place and totals are recomputed.
        assertThat(result).isSameAs(existing);
        assertThat(result.getScope()).isEqualTo(DiscountScope.GLOBAL);
        assertThat(result.getValue()).isEqualByComparingTo("20");
        verify(offerService, times(1)).recomputeTotals(offer);
    }

    @Test
    @DisplayName("remove: totals recomputed on a valid remove (R2.6)")
    void totalsRecomputedOnRemove() {
        authenticateManager();
        OfferEntity offer = draftOffer();
        OfferDiscountEntity existing = existingDiscount(offer);
        when(offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));

        service.remove(OFFER_ID, DISCOUNT_ID);

        // The discount is deleted, removed from the collection, and totals recomputed.
        assertThat(offer.getDiscounts()).doesNotContain(existing);
        verify(offerDiscountDao, times(1)).delete(existing);
        verify(offerService, times(1)).recomputeTotals(offer);
        verify(offerDao, times(1)).save(offer);
    }

    // ------------------------------------------------------------------------------------------
    // fixtures / helpers
    // ------------------------------------------------------------------------------------------

    /** A DRAFT (non-terminal) offer whose estimate yields GLOBAL base 300, LINE 501 base 100. */
    private OfferEntity draftOffer() {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setLines(List.of(
                line(LINE_501_ID, CATEGORY_1_ID, new BigDecimal("100")),
                line(502L, 2L, new BigDecimal("200"))));

        OfferEntity offer = new OfferEntity();
        offer.setId(OFFER_ID);
        offer.setStatus(OfferStatus.DRAFT);
        offer.setEstimate(estimate);
        return offer;
    }

    private static EstimateLineEntity line(Long id, Long categoryId, BigDecimal net) {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setId(categoryId);
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setWorkCategory(category);

        EstimateLineEntity line = new EstimateLineEntity();
        line.setId(id);
        line.setWorkItem(workItem);
        line.setValueNet(net);
        return line;
    }

    /** Registers a managed discount (id {@link #DISCOUNT_ID}) on the offer for edit/remove tests. */
    private OfferDiscountEntity existingDiscount(OfferEntity offer) {
        OfferDiscountEntity discount = new OfferDiscountEntity();
        discount.setId(DISCOUNT_ID);
        discount.setOffer(offer);
        discount.setScope(DiscountScope.LINE);
        discount.setTargetId(LINE_501_ID);
        discount.setKind(DiscountKind.ABSOLUTE);
        discount.setValue(new BigDecimal("10"));
        offer.getDiscounts().add(discount);
        return discount;
    }

    /** Authenticate as a MANAGER executor: numeric principal id whose user resolves to role MANAGER. */
    private void authenticateManager() {
        UserEntity user = new UserEntity();
        user.setId(MANAGER_USER_ID);
        RoleEntity role = new RoleEntity();
        role.setCode("MANAGER");
        user.setRole(role);
        lenient().when(userDao.findById(MANAGER_USER_ID)).thenReturn(Optional.of(user));
        authenticate(String.valueOf(MANAGER_USER_ID), "ROLE_USER");
    }

    /** Authenticate as a non-executor CLIENT: numeric principal id whose user resolves to role CLIENT. */
    private void authenticateClient() {
        UserEntity user = new UserEntity();
        user.setId(CLIENT_USER_ID);
        RoleEntity role = new RoleEntity();
        role.setCode("CLIENT");
        user.setRole(role);
        lenient().when(userDao.findById(CLIENT_USER_ID)).thenReturn(Optional.of(user));
        authenticate(String.valueOf(CLIENT_USER_ID), "ROLE_USER");
    }

    private static void authenticate(String principalName, String authority) {
        SecurityContextHolder.clearContext();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(authority));
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(principalName, "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static void assertApiError(Runnable action, HttpStatus status, String messageCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(status);
                    assertThat(api.getMessageCode()).isEqualTo(messageCode);
                });
    }
}
