package com.foremen.service.procurement.externalorder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ExternalOrderDocumentDao;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.ExternalOrderDocumentEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.link.DocumentPaymentStatusDeriver;
import com.foremen.service.bank.link.ExternalOrderLedgerEffects;
import com.foremen.service.bank.link.ProcurementDocumentPaymentStatusChangedEvent;
import com.foremen.service.procurement.PurchaseRequestOverdue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * FOR-11-03 (R16, R14.5): decides whether an external order is paid by the bank ledger ("the ledger wins")
 * and applies the four cases of R16.3 to R16.5 through the single entry point {@link #reevaluate(Long)}.
 *
 * <p>Cases, with {@code paid} = {@link ExternalOrderPaymentDeriver#derive}:
 * <ol>
 *   <li>{@code SENT_FOR_PAYMENT} and paid: {@code PAID}, source {@code LEDGER}, {@code paidAt} now,
 *       position sync, {@link ExternalOrderPaidEvent} without actor (R16.3).</li>
 *   <li>{@code PAID} by {@code FINANCIER} and paid: source becomes {@code LEDGER}, {@code paidAt} now;
 *       no event (R16.4).</li>
 *   <li>{@code PAID} by {@code LEDGER} and not paid any more: back to {@code SENT_FOR_PAYMENT}, paid fields
 *       cleared, position sync. Refused (409) while a position is {@code IN_DELIVERY} or later (R16.5).</li>
 *   <li>Anything else: nothing changes (a hand-set {@code PAID} stays; {@code DRAFT} and {@code CANCELLED}
 *       are never touched).</li>
 * </ol>
 *
 * <p>All triggers run synchronously in the caller's transaction, so a refused reversal rolls back the link
 * or document change that caused it. The method is idempotent and row-locks the order.
 */
@Service
public class ExternalOrderPaymentService implements ExternalOrderLedgerEffects {

    static final String UNLINK_POSITIONS_IN_DELIVERY = "error.externalOrder.unlink.positionsInDelivery";

    private static final Set<PositionStatus> DELIVERY_OR_LATER = Set.of(
            PositionStatus.IN_DELIVERY, PositionStatus.PARTIALLY_RECEIVED, PositionStatus.RECEIVED);

    private final EntityManager entityManager;
    private final ExternalOrderLineDao lineDao;
    private final ExternalOrderDocumentDao documentDao;
    private final BankTransactionLinkDao linkDao;
    private final PositionOrderSyncService syncService;
    private final ExternalOrderService orderService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ExternalOrderPaymentService(EntityManager entityManager,
                                       ExternalOrderLineDao lineDao,
                                       ExternalOrderDocumentDao documentDao,
                                       BankTransactionLinkDao linkDao,
                                       PositionOrderSyncService syncService,
                                       ExternalOrderService orderService,
                                       ApplicationEventPublisher events,
                                       Clock clock) {
        this.entityManager = entityManager;
        this.lineDao = lineDao;
        this.documentDao = documentDao;
        this.linkDao = linkDao;
        this.syncService = syncService;
        this.orderService = orderService;
        this.events = events;
        this.clock = clock;
    }

    // --- Triggers (R16.6) ---

    /** Port called by {@code LinkEffects} after an {@code EXTERNAL_ORDER_PAYMENT} link changed. */
    @Override
    @Transactional
    public void onLinkChanged(Long externalOrderId) {
        reevaluate(externalOrderId);
    }

    /** R14.5, R16.6: the linked documents of an order were replaced. */
    @EventListener
    @Transactional
    public void onDocumentsChanged(ExternalOrderDocumentsChangedEvent event) {
        reevaluate(event.externalOrderId());
    }

    /** R16.2, R16.6: the derived payment status of a document may have changed. */
    @EventListener
    @Transactional
    public void onDocumentPaymentStatusChanged(ProcurementDocumentPaymentStatusChangedEvent event) {
        Set<Long> orderIds = new LinkedHashSet<>();
        for (ExternalOrderDocumentEntity d : documentDao.findByProcurementDocumentId(event.procurementDocumentId())) {
            orderIds.add(d.getExternalOrder().getId());
        }
        orderIds.forEach(this::reevaluate);
    }

    // --- The single evaluation entry point (R16.6) ---

    @Transactional
    public void reevaluate(Long orderId) {
        ExternalOrderEntity order = entityManager.find(ExternalOrderEntity.class, orderId,
                LockModeType.PESSIMISTIC_WRITE);
        if (order == null) {
            return;
        }
        ExternalOrderStatus status = order.getStatus();
        if (status != ExternalOrderStatus.SENT_FOR_PAYMENT && status != ExternalOrderStatus.PAID) {
            return;
        }
        List<ExternalOrderLineEntity> lines = lineDao.findByExternalOrderId(orderId);
        boolean paid = ExternalOrderPaymentDeriver.derive(
                payableGross(order, lines), ledgerPaidGross(orderId), documentStatuses(orderId));

        if (status == ExternalOrderStatus.SENT_FOR_PAYMENT && paid) {
            markPaidByLedger(order, lines);
        } else if (status == ExternalOrderStatus.PAID && order.getPaidSource() == PaidSource.FINANCIER && paid) {
            String before = orderService.serializeEntity(order);
            order.setPaidSource(PaidSource.LEDGER);
            order.setPaidAt(OffsetDateTime.now(clock));
            entityManager.flush();
            orderService.saveAuditWithSnapshot(before, order, "UPDATE");
        } else if (status == ExternalOrderStatus.PAID && order.getPaidSource() == PaidSource.LEDGER && !paid) {
            revertLedgerPaid(order, lines);
        }
    }

    private void markPaidByLedger(ExternalOrderEntity order, List<ExternalOrderLineEntity> lines) {
        String before = orderService.serializeEntity(order);
        ExternalOrderStatusMachine.requireTransition(order.getStatus(), ExternalOrderStatus.PAID);
        order.setStatus(ExternalOrderStatus.PAID);
        order.setPaidSource(PaidSource.LEDGER);
        order.setPaidAt(OffsetDateTime.now(clock));
        order.setPaidBy(null);
        order.setPaidNote(null);
        entityManager.flush();
        syncService.syncAll(positionIds(lines));
        orderService.saveAuditWithSnapshot(before, order, "UPDATE");
        events.publishEvent(new ExternalOrderPaidEvent(order.getId(), order.getProject().getId(), null));
    }

    private void revertLedgerPaid(ExternalOrderEntity order, List<ExternalOrderLineEntity> lines) {
        if (hasPositionsInDelivery(lines)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, UNLINK_POSITIONS_IN_DELIVERY);
        }
        String before = orderService.serializeEntity(order);
        ExternalOrderStatusMachine.requireTransition(order.getStatus(), ExternalOrderStatus.SENT_FOR_PAYMENT);
        order.setStatus(ExternalOrderStatus.SENT_FOR_PAYMENT);
        clearPaidFields(order);
        entityManager.flush();
        syncService.syncAll(positionIds(lines));
        orderService.saveAuditWithSnapshot(before, order, "UPDATE");
    }

    // --- Shared helpers (also used by the lifecycle service) ---

    static void clearPaidFields(ExternalOrderEntity order) {
        order.setPaidAt(null);
        order.setPaidSource(null);
        order.setPaidBy(null);
        order.setPaidNote(null);
    }

    static List<Long> positionIds(List<ExternalOrderLineEntity> lines) {
        List<Long> ids = new ArrayList<>(lines.size());
        for (ExternalOrderLineEntity l : lines) {
            ids.add(l.getPosition().getId());
        }
        return ids;
    }

    /** True when a position of the order is {@code IN_DELIVERY} or later (owned by FOR-11-04/05). */
    static boolean hasPositionsInDelivery(List<ExternalOrderLineEntity> lines) {
        return lines.stream().anyMatch(l -> DELIVERY_OR_LATER.contains(l.getPosition().getStatus()));
    }

    /** Payable gross of the order: lines gross plus delivery gross (R11.5). */
    static BigDecimal payableGross(ExternalOrderEntity order, List<ExternalOrderLineEntity> lines) {
        List<ExternalOrderMath.LineInput> inputs = new ArrayList<>(lines.size());
        for (ExternalOrderLineEntity l : lines) {
            inputs.add(new ExternalOrderMath.LineInput(
                    l.getUnitPurchaseNet(), l.getQuantity(), percent(l.getPurchaseVatRate())));
        }
        return ExternalOrderMath.compute(inputs, order.getDeliveryCostNet(), percent(order.getDeliveryVatRate()))
                .payableGross();
    }

    /** Today in Europe/Warsaw. */
    LocalDate today() {
        return LocalDate.now(clock.withZone(PurchaseRequestOverdue.ZONE));
    }

    private BigDecimal ledgerPaidGross(Long orderId) {
        BigDecimal sum = linkDao.sumAmountByExternalOrderId(orderId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** R16.2: the derived payment status of every linked document (from its PROCUREMENT links). */
    private List<DocumentPaymentStatus> documentStatuses(Long orderId) {
        List<DocumentPaymentStatus> statuses = new ArrayList<>();
        for (ExternalOrderDocumentEntity d : documentDao.findByExternalOrderId(orderId)) {
            ProcurementDocumentEntity doc = d.getProcurementDocument();
            BigDecimal linked = linkDao.sumAmountByProcurementDocumentId(doc.getId());
            List<BigDecimal> amounts = linked != null && linked.signum() > 0 ? List.of(linked) : List.of();
            statuses.add(DocumentPaymentStatusDeriver.derive(doc.getDocumentTotal(), amounts));
        }
        return statuses;
    }

    private static BigDecimal percent(VatRateEntity rate) {
        return rate == null ? null : rate.getRate();
    }
}
