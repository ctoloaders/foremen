package com.foremen.service.bank.link;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.payment.PaymentTransactionService;
import com.foremen.service.payment.dto.BankTransferInput;
import com.foremen.service.payroll.WorkerPayoutService;

/**
 * FOR-07-04 (Requirements 16.1, 16.4, 16.6–16.9; design §Links and effects) — the side effects of an
 * applied link on the linked domain, run in the link transaction:
 *
 * <ul>
 *   <li>{@code WORKER_PAYOUT} to a {@code PLANNED} payout →
 *       {@link WorkerPayoutService#markPaidFromLedger} with {@code paidAt = bookingDate} and the line's
 *       channel ({@code BANK → BANK_TRANSFER}, {@code CASH → CASH}); the paid event carries
 *       {@code fromLedger = true}, so the cash hook creates nothing. A {@code PAID} payout: no effect.</li>
 *   <li>{@code CLIENT_PAYMENT} → {@link PaymentTransactionService#recordBankTransfer} (channel
 *       {@code BANK_TRANSFER} for a bank line, {@code MANUAL} for a cash line, {@code externalRef =
 *       "bank-txn:{transactionId}:{linkId}"}); the returned payment transaction is stored on the link,
 *       which locks it (R16.7).</li>
 *   <li>{@code PROCUREMENT} → no write: the document payment status is derived
 *       ({@link DocumentPaymentStatusDeriver}) from the link sums; a
 *       {@link ProcurementDocumentPaymentStatusChangedEvent} is published (FOR-11-03).</li>
 *   <li>{@code PROFORMA_PAYMENT} / {@code EXTERNAL_ORDER_PAYMENT} → the ports
 *       {@link ProformaLedgerEffects} / {@link ExternalOrderLedgerEffects} (FOR-11-03).</li>
 *   <li>{@code OTHER_EXPENSE} / {@code OTHER_INCOME} / {@code INTERNAL_TRANSFER} → none.</li>
 * </ul>
 *
 * <p>Removal ({@link #onRemoved}) has no inverse effect on payouts and client payments: a paid payout
 * stays {@code PAID} (R16.8) and a locked client-payment link cannot be removed at all. For the FOR-11-03
 * kinds it re-runs the port recompute, so the proforma / external-order state follows the money back.
 * A {@code PROFORMA_PAYMENT} link of a released proforma is locked.
 */
@Component
public class LinkEffects {

    private final WorkerPayoutService workerPayoutService;
    private final PaymentTransactionService paymentTransactionService;
    private final PaymentTransactionDao paymentTransactionDao;
    private final ProformaLedgerEffects proformaEffects;
    private final ExternalOrderLedgerEffects externalOrderEffects;
    private final ApplicationEventPublisher eventPublisher;

    public LinkEffects(WorkerPayoutService workerPayoutService,
                       PaymentTransactionService paymentTransactionService,
                       PaymentTransactionDao paymentTransactionDao,
                       ProformaLedgerEffects proformaEffects,
                       ExternalOrderLedgerEffects externalOrderEffects,
                       ApplicationEventPublisher eventPublisher) {
        this.workerPayoutService = workerPayoutService;
        this.paymentTransactionService = paymentTransactionService;
        this.paymentTransactionDao = paymentTransactionDao;
        this.proformaEffects = proformaEffects;
        this.externalOrderEffects = externalOrderEffects;
        this.eventPublisher = eventPublisher;
    }

    /** Ledger channel → payout channel ({@code BANK → BANK_TRANSFER}, {@code CASH → CASH}). */
    public static PaymentChannel payoutChannel(LedgerChannel channel) {
        return channel == LedgerChannel.CASH ? PaymentChannel.CASH : PaymentChannel.BANK_TRANSFER;
    }

    /** Ledger channel → client payment channel ({@code BANK → BANK_TRANSFER}, {@code CASH → MANUAL}). */
    public static com.foremen.service.payment.PaymentChannel clientPaymentChannel(LedgerChannel channel) {
        return channel == LedgerChannel.CASH
                ? com.foremen.service.payment.PaymentChannel.MANUAL
                : com.foremen.service.payment.PaymentChannel.BANK_TRANSFER;
    }

    /** The {@code PaymentTransaction} idempotency key of a client-payment link. */
    public static String externalRef(Long transactionId, Long linkId) {
        return "bank-txn:" + transactionId + ":" + linkId;
    }

    /**
     * A link is locked once its client payment was recorded and reconciled (Property 9), or, for a
     * {@code PROFORMA_PAYMENT}, once the proforma was released to the buyer (FOR-11-03 R9).
     */
    public static boolean isLocked(BankTransactionLinkEntity link) {
        if (link.getPaymentTransaction() != null) {
            return true;
        }
        return link.getKind() == LinkKind.PROFORMA_PAYMENT && link.getClientProforma() != null
                && link.getClientProforma().getReleasedAt() != null;
    }

    /**
     * Runs the effect of a freshly saved link (it must already have an id).
     *
     * @param txn  the ledger line
     * @param link the saved link (validated)
     */
    public void apply(BankTransactionEntity txn, BankTransactionLinkEntity link) {
        switch (link.getKind()) {
            case WORKER_PAYOUT -> applyPayout(txn, link.getWorkerPayout());
            case CLIENT_PAYMENT -> applyClientPayment(txn, link);
            case PROCUREMENT -> publishDocumentChanged(link);
            case PROFORMA_PAYMENT -> notifyProforma(link);
            case EXTERNAL_ORDER_PAYMENT -> notifyExternalOrder(link);
            default -> {
                // OTHER_* / INTERNAL_TRANSFER: no external effect.
            }
        }
    }

    /**
     * Runs the reverse effect of a link that was just deleted (the entity is still readable). Payouts and
     * client payments have no inverse (R16.8); {@code PROCUREMENT} publishes the status-changed event;
     * the proforma and external-order kinds re-run their port recompute (FOR-11-03 R7.8).
     *
     * <p>Call after the delete was flushed, so the recompute sees the remaining links only.
     *
     * @param link the removed link
     */
    public void onRemoved(BankTransactionLinkEntity link) {
        switch (link.getKind()) {
            case PROCUREMENT -> publishDocumentChanged(link);
            case PROFORMA_PAYMENT -> notifyProforma(link);
            case EXTERNAL_ORDER_PAYMENT -> notifyExternalOrder(link);
            default -> {
                // WORKER_PAYOUT / CLIENT_PAYMENT / OTHER_* / INTERNAL_TRANSFER: no inverse effect.
            }
        }
    }

    private void publishDocumentChanged(BankTransactionLinkEntity link) {
        if (link.getProcurementDocument() != null) {
            eventPublisher.publishEvent(
                    new ProcurementDocumentPaymentStatusChangedEvent(link.getProcurementDocument().getId()));
        }
    }

    private void notifyProforma(BankTransactionLinkEntity link) {
        if (link.getClientProforma() == null) {
            return;
        }
        proformaEffects.onLinkChanged(link.getClientProforma().getId());
    }

    private void notifyExternalOrder(BankTransactionLinkEntity link) {
        if (link.getExternalOrder() == null) {
            return;
        }
        externalOrderEffects.onLinkChanged(link.getExternalOrder().getId());
    }

    private void applyPayout(BankTransactionEntity txn, WorkerPayoutEntity payout) {
        if (payout != null && payout.getStatus() == PayoutStatus.PLANNED) {
            workerPayoutService.markPaidFromLedger(payout.getId(), txn.getBookingDate(),
                    payoutChannel(txn.getChannel()));
        }
    }

    private void applyClientPayment(BankTransactionEntity txn, BankTransactionLinkEntity link) {
        Long paymentTransactionId = paymentTransactionService.recordBankTransfer(
                link.getPaymentSchedule().getId(),
                new BankTransferInput(link.getAmount(), txn.getCurrency(), txn.getBookingDate(),
                        externalRef(txn.getId(), link.getId()), txn.getTitle(),
                        clientPaymentChannel(txn.getChannel())));
        link.setPaymentTransaction(paymentTransactionDao.findById(paymentTransactionId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, BankLinkService.NOT_FOUND)));
    }
}
