package com.foremen.service.procurement.position;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.controller.model.BuyerAddedPositionRequest;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.PurchasePositionService;
import com.foremen.service.PurchaseRequestService;
import com.foremen.service.model.ExternalOrderServiceExtendedModel;
import com.foremen.service.model.PurchasePositionServiceExtendedModel;
import com.foremen.service.procurement.externalorder.ExternalOrderService;

import jakarta.persistence.EntityManager;

/** FOR-11-06 task 7.7 (R10.1 - R10.3, R10.6): buyer-added creation, flags, edge, attach targets, event. */
class BuyerAddedPositionServiceTest {

    private PurchasePositionService positionService;
    private PurchasePositionDao dao;
    private PositionLifecycleService lifecycle;
    private ExternalOrderService orderService;
    private PurchaseRequestService requestService;
    private ApplicationEventPublisher events;
    private BuyerAddedPositionService service;
    private PurchasePositionEntity entity;

    @BeforeEach
    void setUp() {
        authenticate("ROLE_BUYER");
        positionService = mock(PurchasePositionService.class);
        dao = mock(PurchasePositionDao.class);
        lifecycle = mock(PositionLifecycleService.class);
        orderService = mock(ExternalOrderService.class);
        requestService = mock(PurchaseRequestService.class);
        events = mock(ApplicationEventPublisher.class);
        service = new BuyerAddedPositionService(positionService, lifecycle, orderService, requestService, events);

        entity = new PurchasePositionEntity();
        entity.setId(7L);
        PurchasePositionServiceExtendedModel created = new PurchasePositionServiceExtendedModel();
        created.setId(7L);
        created.setQuantity(new BigDecimal("3"));
        created.setPlannedPurchasePrice(new BigDecimal("10.00"));
        when(positionService.create(any(PurchasePositionServiceExtendedModel.class))).thenReturn(created);
        when(positionService.getDao()).thenReturn(dao);
        when(positionService.getEntityManager()).thenReturn(mock(EntityManager.class));
        when(dao.findById(7L)).thenReturn(Optional.of(entity));
        when(positionService.findById(7L)).thenReturn(created);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "42", "n/a", List.of(new SimpleGrantedAuthority(role))));
    }

    private static BuyerAddedPositionRequest request(Long orderId, Long requestId) {
        return new BuyerAddedPositionRequest(5L, 9L, new BigDecimal("3"), 1L, new BigDecimal("10.00"),
                null, null, null, orderId, requestId, "note");
    }

    @Test
    void createsAgreedFinishingPositionFlagsItAndMovesItThroughTheBuyerAddedEdge() {
        service.create(request(null, null));

        ArgumentCaptor<PurchasePositionServiceExtendedModel> model =
                ArgumentCaptor.forClass(PurchasePositionServiceExtendedModel.class);
        verify(positionService).create(model.capture());
        assertThat(model.getValue().getSourcingPath()).isEqualTo(SourcingPath.AGREED);
        assertThat(model.getValue().getKind()).isEqualTo(PositionKind.FINISHING);
        assertThat(model.getValue().getProjectId()).isEqualTo(5L);

        assertThat(entity.isAddedByBuyer()).isTrue();
        assertThat(entity.isClientUnpaid()).isTrue();
        InOrder order = inOrder(dao, lifecycle);
        order.verify(dao).save(entity);
        order.verify(lifecycle).transitionBuyerAdded(7L);
    }

    @Test
    void publishesTheEventWithTheActorAndAttachesToTheRequestedTargets() {
        when(orderService.findById(11L)).thenReturn(new ExternalOrderServiceExtendedModel());

        service.create(request(11L, 12L));

        verify(orderService).replaceLines(any(Long.class), any());
        verify(requestService).attachPositions(12L, List.of(7L));
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new PositionAddedByBuyerEvent(7L, 5L, 42L));
    }

    @Test
    void withoutAttachTargetsNeitherOrderNorRequestIsTouched() {
        service.create(request(null, null));

        verifyNoInteractions(orderService, requestService);
    }

    @Test
    void rolesOutsideBuyerManagerAdminAreForbiddenBeforeAnythingIsCreated() {
        for (String role : List.of("ROLE_FOREMAN", "ROLE_CLIENT", "ROLE_FINANCIER")) {
            authenticate(role);
            assertThatThrownBy(() -> service.create(request(null, null)))
                    .isInstanceOfSatisfying(ForemenApiException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        verify(positionService, never()).create(any(PurchasePositionServiceExtendedModel.class));
        verifyNoInteractions(lifecycle, events);
    }
}
