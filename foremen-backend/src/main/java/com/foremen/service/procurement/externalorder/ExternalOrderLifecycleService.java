package com.foremen.service.procurement.externalorder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ExternalOrderDocumentDao;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.ExternalOrderServiceExtendedModel;
import com.foremen.service.model.mapper.ExternalOrderServiceMapper;
import com.foremen.service.procurement.PurchaseRequestOverdue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * FOR-11-03 (R15): status changes of an external order after the draft: {@link #send}, {@link #markPaid},
 * {@link #returnToBuyer}, {@link #unmarkPaid} and {@link #cancel}.
 *
 * <p>Every method is transactional, takes a pessimistic row lock, checks the pure
 * {@link ExternalOrderStatusMachine}, then runs {@link PositionOrderSyncService#syncAll} for the order's
 * positions and publishes the event after the change is flushed. Ledger-driven changes are
 * {@link ExternalOrderPaymentService}. Role rules (buyer versus financier) are enforced by ABAC on the
 * controller; project access is checked here.
 *
 * <p>The cancel reason is not stored (the order has no column for it); it only exists in the request.
 */
@Service
public class ExternalOrderLifecycleService {

    static final String SEND_NO_LINES = "error.externalOrder.send.noLines";
    static final String SEND_NO_DOCUMENT = "error.externalOrder.send.noDocument";
    static final String SEND_ZERO_TOTAL = "error.externalOrder.send.zeroTotal";
    static final String MARK_PAID_DATE_FUTURE = "error.externalOrder.markPaid.dateInFuture";
    static final String MARK_PAID_NOTE_TOO_LONG = "error.externalOrder.markPaid.noteTooLong";
    static final String RETURN_REASON_REQUIRED = "error.externalOrder.return.reasonRequired";
    static final String UNMARK_LEDGER_PAID = "error.externalOrder.unmark.ledgerPaid";
    static final String UNMARK_POSITIONS_IN_DELIVERY = "error.externalOrder.unmark.positionsInDelivery";
    static final String CANCEL_HAS_PAYMENTS = "error.externalOrder.cancel.hasPayments";
    static final String CANCEL_POSITIONS_IN_DELIVERY = "error.externalOrder.cancel.positionsInDelivery";
    static final int MAX_TEXT_LENGTH = 500;

    private final EntityManager entityManager;
    private final ExternalOrderLineDao lineDao;
    private final ExternalOrderDocumentDao documentDao;
    private final BankTransactionLinkDao linkDao;
    private final ExternalOrderService orderService;
    private final ExternalOrderPaymentService paymentService;
    private final PositionOrderSyncService syncService;
    private final ExternalOrderServiceMapper mapper;
    private final ApplicationEventPublisher events;

    public ExternalOrderLifecycleService(EntityManager entityManager,
                                         ExternalOrderLineDao lineDao,
                                         ExternalOrderDocumentDao documentDao,
                                         BankTransactionLinkDao linkDao,
                                         ExternalOrderService orderService,
                                         ExternalOrderPaymentService paymentService,
                                         PositionOrderSyncService syncService,
                                         ExternalOrderServiceMapper mapper,
                                         ApplicationEventPublisher events) {
        this.entityManager = entityManager;
        this.lineDao = lineDao;
        this.documentDao = documentDao;
        this.linkDao = linkDao;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.syncService = syncService;
        this.mapper = mapper;
        this.events = events;
    }

    /** R15.1: {@code DRAFT -> SENT_FOR_PAYMENT}. An order already covered by paid documents becomes paid at once. */
    @Transactional
    public ExternalOrderServiceExtendedModel send(Long id) {
        ExternalOrderEntity order = lock(id);
        ExternalOrderStatusMachine.requireTransition(order.getStatus(), ExternalOrderStatus.SENT_FOR_PAYMENT);
        List<ExternalOrderLineEntity> lines = lineDao.findByExternalOrderId(id);
        if (lines.isEmpty()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SEND_NO_LINES);
        }
        boolean hasReference = order.getReferenceNumber() != null && !order.getReferenceNumber().isBlank();
        if (!hasReference && documentDao.findByExternalOrderId(id).isEmpty()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SEND_NO_DOCUMENT);
        }
        if (ExternalOrderPaymentService.payableGross(order, lines).signum() <= 0) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SEND_ZERO_TOTAL);
        }
        for (ExternalOrderLineEntity l : lines) {
            PurchasePositionEntity p = l.getPosition();
            if (p.getStatus() != PositionStatus.WITH_BUYER) {
                throw conflict(ExternalOrderService.POSITION_NOT_PURCHASABLE, p.getId());
            }
            if (CeilingCheck.exceeds(l.getUnitPurchaseNet(), p.getSalePrice()) && !l.isAboveCeilingAcknowledged()) {
                throw conflict(ExternalOrderService.ABOVE_CEILING, p.getId());
            }
        }

        String before = orderService.serializeEntity(order);
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        order.setSentAt(OffsetDateTime.now());
        order.setReturnReason(null);
        entityManager.flush();
        syncService.syncAll(ExternalOrderPaymentService.positionIds(lines));
        orderService.saveAuditWithSnapshot(before, order, "UPDATE");
        events.publishEvent(new ExternalOrderSentEvent(id, order.getProject().getId(), currentUserId()));
        paymentService.reevaluate(id);
        return mapper.toServiceExtendedModel(order);
    }

    /** R15.3: the financier marks a sent order paid by hand. */
    @Transactional
    public ExternalOrderServiceExtendedModel markPaid(Long id, LocalDate paidDate, String note) {
        ExternalOrderEntity order = lock(id);
        ExternalOrderStatusMachine.requireTransition(order.getStatus(), ExternalOrderStatus.PAID);
        LocalDate today = paymentService.today();
        LocalDate date = paidDate == null ? today : paidDate;
        if (date.isAfter(today)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MARK_PAID_DATE_FUTURE);
        }
        String cleanNote = clean(note);
        if (cleanNote != null && cleanNote.length() > MAX_TEXT_LENGTH) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MARK_PAID_NOTE_TOO_LONG);
        }
        List<ExternalOrderLineEntity> lines = lineDao.findByExternalOrderId(id);

        String before = orderService.serializeEntity(order);
        Long actor = currentUserId();
        order.setStatus(ExternalOrderStatus.PAID);
        order.setPaidSource(PaidSource.FINANCIER);
        order.setPaidAt(date.atStartOfDay(PurchaseRequestOverdue.ZONE).toOffsetDateTime());
        order.setPaidBy(actor == null ? null : entityManager.getReference(UserEntity.class, actor));
        order.setPaidNote(cleanNote);
        entityManager.flush();
        syncService.syncAll(ExternalOrderPaymentService.positionIds(lines));
        orderService.saveAuditWithSnapshot(before, order, "UPDATE");
        events.publishEvent(new ExternalOrderPaidEvent(id, order.getProject().getId(), actor));
        return mapper.toServiceExtendedModel(order);
    }

    /** R15.4: the financier returns a sent order to the buyer with a reason. */
    @Transactional
    public ExternalOrderServiceExtendedModel returnToBuyer(Long id, String reason) {
        ExternalOrderEntity order = lock(id);
        ExternalOrderStatusMachine.requireTransition(order.getStatus(), ExternalOrderStatus.DRAFT);
        String cleanReason = clean(reason);
        if (cleanReason == null || cleanReason.length() > MAX_TEXT_LENGTH) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, RETURN_REASON_REQUIRED);
        }
        List<ExternalOrderLineEntity> lines = lineDao.findByExternalOrderId(id);

        String before = orderService.serializeEntity(order);
        order.setStatus(ExternalOrderStatus.DRAFT);
        order.setReturnReason(cleanReason);
        order.setSentAt(null);
        entityManager.flush();
        syncService.syncAll(ExternalOrderPaymentService.positionIds(lines));
        orderService.saveAuditWithSnapshot(before, order, "UPDATE");
        return mapper.toServiceExtendedModel(order);
    }

    /** R15.5: reverts a hand-marked paid order to {@code SENT_FOR_PAYMENT}; a ledger-paid order is refused. */
    @Transactional
    public ExternalOrderServiceExtendedModel unmarkPaid(Long id) {
        ExternalOrderEntity order = lock(id);
        if (order.getStatus() == ExternalOrderStatus.PAID && order.getPaidSource() == PaidSource.LEDGER) {
            throw new ForemenApiException(HttpStatus.CONFLICT, UNMARK_LEDGER_PAID);
        }
        ExternalOrderStatusMachine.requireTransition(order.getStatus(), ExternalOrderStatus.SENT_FOR_PAYMENT);
        List<ExternalOrderLineEntity> lines = lineDao.findByExternalOrderId(id);
        if (ExternalOrderPaymentService.hasPositionsInDelivery(lines)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, UNMARK_POSITIONS_IN_DELIVERY);
        }

        String before = orderService.serializeEntity(order);
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        ExternalOrderPaymentService.clearPaidFields(order);
        entityManager.flush();
        syncService.syncAll(ExternalOrderPaymentService.positionIds(lines));
        orderService.saveAuditWithSnapshot(before, order, "UPDATE");
        return mapper.toServiceExtendedModel(order);
    }

    /** R15.6: cancels a draft or sent order that has no ledger payment. */
    @Transactional
    public ExternalOrderServiceExtendedModel cancel(Long id) {
        ExternalOrderEntity order = lock(id);
        ExternalOrderStatusMachine.requireTransition(order.getStatus(), ExternalOrderStatus.CANCELLED);
        BigDecimal ledgerPaid = linkDao.sumAmountByExternalOrderId(id);
        if (ledgerPaid != null && ledgerPaid.signum() > 0) {
            throw new ForemenApiException(HttpStatus.CONFLICT, CANCEL_HAS_PAYMENTS);
        }
        List<ExternalOrderLineEntity> lines = lineDao.findByExternalOrderId(id);
        if (ExternalOrderPaymentService.hasPositionsInDelivery(lines)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, CANCEL_POSITIONS_IN_DELIVERY);
        }

        String before = orderService.serializeEntity(order);
        order.setStatus(ExternalOrderStatus.CANCELLED);
        order.setCancelledAt(OffsetDateTime.now());
        entityManager.flush();
        syncService.syncAll(ExternalOrderPaymentService.positionIds(lines));
        orderService.saveAuditWithSnapshot(before, order, "UPDATE");
        return mapper.toServiceExtendedModel(order);
    }

    // --- helpers ---

    private ExternalOrderEntity lock(Long id) {
        orderService.assertProjectAccess(id);
        ExternalOrderEntity order = entityManager.find(ExternalOrderEntity.class, id,
                LockModeType.PESSIMISTIC_WRITE);
        if (order == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ExternalOrderService.ENTITY_NOT_FOUND, id);
        }
        return order;
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        return t.isEmpty() ? null : t;
    }

    private static ForemenApiException conflict(String key, Long id) {
        return new ForemenApiException(HttpStatus.CONFLICT, key, id).withParams(java.util.Map.of("id", id));
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
