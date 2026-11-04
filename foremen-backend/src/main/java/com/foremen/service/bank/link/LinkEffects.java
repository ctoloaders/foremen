package com.foremen.service.bank.link;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.LedgerChannel;
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
 *       ({@link DocumentPaymentStatusDeriver}) from the link sums.</li>
 *   <li>{@code OTHER_EXPENSE} / {@code OTHER_INCOME} / {@code INTERNAL_TRANSFER} → none.</li>
 * </ul>
 *
 * <p>Removal has no inverse effect: a paid payout stays {@code PAID} (R16.8) and a locked
 * client-payment link cannot be removed at all.
 */
@Component
public class LinkEffects {

    private final WorkerPayoutService workerPayoutService;
    private final PaymentTransactionService paymentTransactionService;
    private final PaymentTransactionDao paymentTransactionDao;

    public LinkEffects(WorkerPayoutService workerPayoutService,
                       PaymentTransactionService paymentTransactionService,
                       PaymentTransactionDao paymentTransactionDao) {
        this.workerPayoutService = workerPayoutService;
        this.paymentTransactionService = paymentTransactionService;
        this.paymentTransactionDao = paymentTransactionDao;
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

    /** A link is locked once its client payment was recorded and reconciled (Property 9). */
    public static boolean isLocked(BankTransactionLinkEntity link) {
        return link.getPaymentTransaction() != null;
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
            default -> {
                // PROCUREMENT: derived status only; OTHER_* / INTERNAL_TRANSFER: no external effect.
            }
        }
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
