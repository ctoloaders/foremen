package com.foremen.service.procurement.externalorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ExternalOrderDocumentDao;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.model.ExternalOrderDocumentEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.link.ProcurementDocumentPaymentStatusChangedEvent;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** FOR-11-03 task 4.3: R16.3 - R16.6, R14.5 (the four cases of {@code reevaluate}). */
class ExternalOrderPaymentServiceTest {

    private static final Long ID = 9L;

    private final EntityManager em = mock(EntityManager.class);
    private final ExternalOrderLineDao lineDao = mock(ExternalOrderLineDao.class);
    private final ExternalOrderDocumentDao documentDao = mock(ExternalOrderDocumentDao.class);
    private final BankTransactionLinkDao linkDao = mock(BankTransactionLinkDao.class);
    private final PositionOrderSyncService sync = mock(PositionOrderSyncService.class);
    private final ExternalOrderService orderService = mock(ExternalOrderService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC);
    private final ExternalOrderPaymentService service = new ExternalOrderPaymentService(
            em, lineDao, documentDao, linkDao, sync, orderService, events, clock);

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
        position = new PurchasePositionEntity();
        position.setId(21L);
        position.setStatus(PositionStatus.ORDERED);
        ExternalOrderLineEntity line = new ExternalOrderLineEntity();
        line.setPosition(position);
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPurchaseNet(new BigDecimal("50.00"));
        when(em.find(ExternalOrderEntity.class, ID, LockModeType.PESSIMISTIC_WRITE)).thenReturn(order);
        when(lineDao.findByExternalOrderId(ID)).thenReturn(List.of(line));
        when(documentDao.findByExternalOrderId(ID)).thenReturn(List.of());
        when(linkDao.sumAmountByExternalOrderId(ID)).thenReturn(BigDecimal.ZERO);
        when(orderService.serializeEntity(any())).thenReturn("{}");
    }

    @Test
    void sentOrderCoveredByLedgerLinksBecomesPaidByLedgerAndNotifiesBuyer() {
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        when(linkDao.sumAmountByExternalOrderId(ID)).thenReturn(new BigDecimal("100.00"));

        service.reevaluate(ID);

        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.PAID);
        assertThat(order.getPaidSource()).isEqualTo(PaidSource.LEDGER);
        assertThat(order.getPaidAt()).isNotNull();
        verify(sync).syncAll(List.of(21L));
        ArgumentCaptor<ExternalOrderPaidEvent> ev = ArgumentCaptor.forClass(ExternalOrderPaidEvent.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().actorUserId()).isNull();
        assertThat(ev.getValue().projectId()).isEqualTo(3L);
    }

    @Test
    void sentOrderWithEveryDocumentPaidBecomesPaidByLedger() {
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        ProcurementDocumentEntity doc = new ProcurementDocumentEntity();
        doc.setId(70L);
        doc.setDocumentTotal(new BigDecimal("100.00"));
        ExternalOrderDocumentEntity link = new ExternalOrderDocumentEntity();
        link.setProcurementDocument(doc);
        when(documentDao.findByExternalOrderId(ID)).thenReturn(List.of(link));
        when(linkDao.sumAmountByProcurementDocumentId(70L)).thenReturn(new BigDecimal("100.00"));

        service.reevaluate(ID);

        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.PAID);
        assertThat(order.getPaidSource()).isEqualTo(PaidSource.LEDGER);
    }

    @Test
    void sentOrderPartiallyCoveredStaysSent() {
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        when(linkDao.sumAmountByExternalOrderId(ID)).thenReturn(new BigDecimal("99.99"));

        service.reevaluate(ID);

        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
        verify(events, never()).publishEvent(any(Object.class));
        verify(sync, never()).syncAll(anyCollection());
    }

    @Test
    void ledgerWinsOverHandMarkedPaidWithoutANewNotification() {
        order.setStatus(ExternalOrderStatus.PAID);
        order.setPaidSource(PaidSource.FINANCIER);
        when(linkDao.sumAmountByExternalOrderId(ID)).thenReturn(new BigDecimal("100.00"));

        service.reevaluate(ID);

        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.PAID);
        assertThat(order.getPaidSource()).isEqualTo(PaidSource.LEDGER);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void lostLedgerEvidenceReturnsLedgerPaidOrderToSentAndReversesStages() {
        order.setStatus(ExternalOrderStatus.PAID);
        order.setPaidSource(PaidSource.LEDGER);
        order.setPaidAt(OffsetDateTime.now());

        service.reevaluate(ID);

        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertThat(order.getPaidSource()).isNull();
        assertThat(order.getPaidAt()).isNull();
        verify(sync).syncAll(List.of(21L));
    }

    @Test
    void lostLedgerEvidenceIsRefusedWhilePositionsAreInDelivery() {
        order.setStatus(ExternalOrderStatus.PAID);
        order.setPaidSource(PaidSource.LEDGER);
        position.setStatus(PositionStatus.IN_DELIVERY);

        ForemenApiException ex = catchThrowableOfType(ForemenApiException.class, () -> service.reevaluate(ID));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getMessageCode()).isEqualTo(ExternalOrderPaymentService.UNLINK_POSITIONS_IN_DELIVERY);
        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.PAID);
    }

    @Test
    void handMarkedPaidStaysPaidWhenNoLedgerEvidence() {
        order.setStatus(ExternalOrderStatus.PAID);
        order.setPaidSource(PaidSource.FINANCIER);

        service.reevaluate(ID);

        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.PAID);
        assertThat(order.getPaidSource()).isEqualTo(PaidSource.FINANCIER);
    }

    @Test
    void draftAndCancelledOrdersAreNeverTouched() {
        when(linkDao.sumAmountByExternalOrderId(ID)).thenReturn(new BigDecimal("100.00"));
        for (ExternalOrderStatus s : List.of(ExternalOrderStatus.DRAFT, ExternalOrderStatus.CANCELLED)) {
            order.setStatus(s);
            service.reevaluate(ID);
            assertThat(order.getStatus()).isEqualTo(s);
        }
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void documentPaymentStatusChangeReevaluatesEveryAttachedOrder() {
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        when(linkDao.sumAmountByExternalOrderId(ID)).thenReturn(new BigDecimal("100.00"));
        ExternalOrderDocumentEntity attachment = new ExternalOrderDocumentEntity();
        attachment.setExternalOrder(order);
        when(documentDao.findByProcurementDocumentId(70L)).thenReturn(List.of(attachment));

        service.onDocumentPaymentStatusChanged(new ProcurementDocumentPaymentStatusChangedEvent(70L));

        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.PAID);
    }

    @Test
    void ledgerLinkAndDocumentsChangedTriggersCallReevaluate() {
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        when(linkDao.sumAmountByExternalOrderId(ID)).thenReturn(new BigDecimal("100.00"));

        service.onLinkChanged(ID);
        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.PAID);

        when(linkDao.sumAmountByExternalOrderId(ID)).thenReturn(BigDecimal.ZERO);
        service.onDocumentsChanged(new ExternalOrderDocumentsChangedEvent(ID));
        assertThat(order.getStatus()).isEqualTo(ExternalOrderStatus.SENT_FOR_PAYMENT);
    }
}
