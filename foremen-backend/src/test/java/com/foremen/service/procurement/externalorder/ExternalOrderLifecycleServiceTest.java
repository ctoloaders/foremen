package com.foremen.service.procurement.externalorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ExternalOrderDocumentDao;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.mapper.ExternalOrderServiceMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** FOR-11-03 task 4.3: R15.1 - R15.6. */
class ExternalOrderLifecycleServiceTest {

    private static final Long ID = 9L;

    private final EntityManager em = mock(EntityManager.class);
    private final ExternalOrderLineDao lineDao = mock(ExternalOrderLineDao.class);
    private final ExternalOrderDocumentDao documentDao = mock(ExternalOrderDocumentDao.class);
    private final BankTransactionLinkDao linkDao = mock(BankTransactionLinkDao.class);
    private final ExternalOrderService orderService = mock(ExternalOrderService.class);
    private final ExternalOrderPaymentService payment = mock(ExternalOrderPaymentService.class);
    private final PositionOrderSyncService sync = mock(PositionOrderSyncService.class);
    private final ExternalOrderServiceMapper mapper = mock(ExternalOrderServiceMapper.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ExternalOrderLifecycleService service = new ExternalOrderLifecycleService(
            em, lineDao, documentDao, linkDao, orderService, payment, sync, mapper, events);

    private ExternalOrderEntity order;
    private PurchasePositionEntity position;

    @BeforeEach
    void setUp() {
        ProjectEntity project = new ProjectEntity();
        project.setId(3L);
        order = new ExternalOrderEntity();
        order.setId(ID);
        order.setProject(project);
        order.setDeliveryCostNet(BigDecimal.ZERO);
        order.setReferenceNumber("ZAM-1");
        position = new PurchasePositionEntity();
        position.setId(21L);
        position.setStatus(PositionStatus.WITH_BUYER);
        position.setSalePrice(new BigDecimal("80.00"));
        ExternalOrderLineEntity line = new ExternalOrderLineEntity();
        line.setPosition(position);
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPurchaseNet(new BigDecimal("50.00"));
        when(em.find(ExternalOrderEntity.class, ID, LockModeType.PESSIMISTIC_WRITE)).thenReturn(order);
        when(lineDao.findByExternalOrderId(ID)).thenReturn(List.of(line));
        when(documentDao.findByExternalOrderId(ID)).thenReturn(List.of());
        when(linkDao.sumAmountByExternalOrderId(ID)).thenReturn(BigDecimal.ZERO);
        when(orderService.serializeEntity(any())).thenReturn("{}");
        when(payment.today()).thenReturn(LocalDate.of(2026, 3, 10));
    }

    @Test
    void sendMovesDraftToSentSyncsPositionsPublishesEventAndReevaluates() {
        order.setStatus(ExternalOrderStatus.DRAFT);
        order.setReturnReason("old");

        service.send(ID);

        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertThat(order.getSentAt()).isNotNull();
        assertThat(order.getReturnReason()).isNull();
        verify(sync).syncAll(List.of(21L));
        verify(events).publishEvent(any(ExternalOrderSentEvent.class));
        verify(payment).reevaluate(ID);
    }

    @Test
    void sendNeedsADocumentOrReferenceAndLines() {
        order.setStatus(ExternalOrderStatus.DRAFT);
        order.setReferenceNumber(" ");
        assertCode(() -> service.send(ID), HttpStatus.CONFLICT, ExternalOrderLifecycleService.SEND_NO_DOCUMENT);
        order.setReferenceNumber("X");
        when(lineDao.findByExternalOrderId(ID)).thenReturn(List.of());
        assertCode(() -> service.send(ID), HttpStatus.CONFLICT, ExternalOrderLifecycleService.SEND_NO_LINES);
        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.DRAFT);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void sendRefusesUnacknowledgedPriceAboveCeiling() {
        order.setStatus(ExternalOrderStatus.DRAFT);
        position.setSalePrice(new BigDecimal("40.00"));
        assertCode(() -> service.send(ID), HttpStatus.CONFLICT, ExternalOrderService.ABOVE_CEILING);
    }

    @Test
    void markPaidByFinancierSetsSourceAndPublishesEvent() {
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);

        service.markPaid(ID, LocalDate.of(2026, 3, 9), "  przelew ");

        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.PAID);
        assertThat(order.getPaidSource()).isEqualTo(PaidSource.FINANCIER);
        assertThat(order.getPaidNote()).isEqualTo("przelew");
        assertThat(order.getPaidAt()).isNotNull();
        verify(sync).syncAll(List.of(21L));
        verify(events).publishEvent(any(ExternalOrderPaidEvent.class));
    }

    @Test
    void markPaidRejectsFutureDateAndWrongStatus() {
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertCode(() -> service.markPaid(ID, LocalDate.of(2026, 3, 11), null),
                HttpStatus.BAD_REQUEST, ExternalOrderLifecycleService.MARK_PAID_DATE_FUTURE);
        order.setStatus(ExternalOrderStatus.DRAFT);
        assertCode(() -> service.markPaid(ID, null, null),
                HttpStatus.CONFLICT, ExternalOrderStatusMachine.NOT_ALLOWED);
    }

    @Test
    void setPaymentLinkOnSentOrderStoresTrimmedLinkAndPublishesEventOnlyWhenChanged() {
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);

        service.setPaymentLink(ID, "  https://pay.example.com/o/1 ");
        assertThat(order.getPaymentLink()).isEqualTo("https://pay.example.com/o/1");
        verify(events).publishEvent(any(ExternalOrderPaymentLinkChangedEvent.class));

        service.setPaymentLink(ID, "https://pay.example.com/o/1");
        verify(events, org.mockito.Mockito.times(1)).publishEvent(any(ExternalOrderPaymentLinkChangedEvent.class));
    }

    @Test
    void setPaymentLinkOnDraftPublishesNothingAndBlankClears() {
        order.setStatus(ExternalOrderStatus.DRAFT);
        service.setPaymentLink(ID, "http://pay.example.com/x");
        assertThat(order.getPaymentLink()).isEqualTo("http://pay.example.com/x");
        service.setPaymentLink(ID, "  ");
        assertThat(order.getPaymentLink()).isNull();
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void setPaymentLinkRejectsInvalidUrlsAndLockedStatuses() {
        order.setStatus(ExternalOrderStatus.DRAFT);
        for (String bad : List.of("javascript:alert(1)", "ftp://x.example.com/a", "/relative/path",
                "https://" + "a".repeat(1000))) {
            assertCode(() -> service.setPaymentLink(ID, bad),
                    HttpStatus.BAD_REQUEST, ExternalOrderService.PAYMENT_LINK_INVALID);
        }
        for (ExternalOrderStatus locked : List.of(ExternalOrderStatus.PAID, ExternalOrderStatus.CANCELLED)) {
            order.setStatus(locked);
            assertCode(() -> service.setPaymentLink(ID, "https://pay.example.com/1"),
                    HttpStatus.CONFLICT, ExternalOrderLifecycleService.PAYMENT_LINK_LOCKED);
        }
    }

    @Test
    void returnToBuyerStoresReasonAndClearsSentAt() {
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        order.setSentAt(OffsetDateTime.now());

        service.returnToBuyer(ID, " brak faktury ");

        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.DRAFT);
        assertThat(order.getReturnReason()).isEqualTo("brak faktury");
        assertThat(order.getSentAt()).isNull();
        verify(sync).syncAll(List.of(21L));
    }

    @Test
    void returnToBuyerNeedsAReason() {
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertCode(() -> service.returnToBuyer(ID, "  "),
                HttpStatus.BAD_REQUEST, ExternalOrderLifecycleService.RETURN_REASON_REQUIRED);
        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
    }

    @Test
    void unmarkPaidRevertsFinancierPaidAndRefusesLedgerPaid() {
        order.setStatus(ExternalOrderStatus.PAID);
        order.setPaidSource(PaidSource.LEDGER);
        assertCode(() -> service.unmarkPaid(ID), HttpStatus.CONFLICT, ExternalOrderLifecycleService.UNMARK_LEDGER_PAID);

        order.setPaidSource(PaidSource.FINANCIER);
        order.setPaidAt(OffsetDateTime.now());
        service.unmarkPaid(ID);

        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertThat(order.getPaidSource()).isNull();
        assertThat(order.getPaidAt()).isNull();
        verify(sync).syncAll(List.of(21L));
    }

    @Test
    void cancelRulesPaymentsDeliveryAndPaidStatus() {
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        when(linkDao.sumAmountByExternalOrderId(ID)).thenReturn(new BigDecimal("1.00"));
        assertCode(() -> service.cancel(ID), HttpStatus.CONFLICT, ExternalOrderLifecycleService.CANCEL_HAS_PAYMENTS);

        when(linkDao.sumAmountByExternalOrderId(ID)).thenReturn(BigDecimal.ZERO);
        position.setStatus(PositionStatus.IN_DELIVERY);
        assertCode(() -> service.cancel(ID), HttpStatus.CONFLICT,
                ExternalOrderLifecycleService.CANCEL_POSITIONS_IN_DELIVERY);

        position.setStatus(PositionStatus.ORDERED);
        service.cancel(ID);
        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isNotNull();
        verify(sync).syncAll(List.of(21L));

        order.setStatus(ExternalOrderStatus.PAID);
        assertCode(() -> service.cancel(ID), HttpStatus.CONFLICT, ExternalOrderStatusMachine.NOT_ALLOWED);
    }

    private static void assertCode(Runnable call, HttpStatus status, String key) {
        ForemenApiException ex = catchThrowableOfType(ForemenApiException.class, call::run);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(status);
        assertThat(ex.getMessageCode()).isEqualTo(key);
    }
}
