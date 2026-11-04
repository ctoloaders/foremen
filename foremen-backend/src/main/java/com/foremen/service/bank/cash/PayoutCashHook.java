package com.foremen.service.bank.cash;

import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.SystemSource;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.service.bank.link.LinkRequest;
import com.foremen.service.payroll.WorkerPayoutPaidEvent;

/**
 * FOR-07-04 (Requirements 27.4, 27.5; design §Cash ledger, Property 11) — records a payroll payout paid in
 * cash as a SYSTEM cash expense entry linked {@code WORKER_PAYOUT} to the payout.
 *
 * <p>A synchronous {@link EventListener} on {@link WorkerPayoutPaidEvent}, i.e. it runs inside the payroll
 * transaction: a failure rolls the "mark paid" back. It acts only when {@code channel == CASH} and the
 * payout was not paid from a ledger line ({@code fromLedger == false}); {@code BANK_TRANSFER} payouts and
 * ledger-originated payments create nothing. A payout that already has its SYSTEM entry is skipped, so
 * at most one entry exists per payout. The entry goes to the default cash account of the payout currency,
 * which is created ({@code "Cash {currency}"}) when missing.
 */
@Component
public class PayoutCashHook {

    private static final Logger log = LoggerFactory.getLogger(PayoutCashHook.class);

    private final CashLedgerService cashLedgerService;
    private final WorkerPayoutDao workerPayoutDao;

    public PayoutCashHook(CashLedgerService cashLedgerService, WorkerPayoutDao workerPayoutDao) {
        this.cashLedgerService = cashLedgerService;
        this.workerPayoutDao = workerPayoutDao;
    }

    @EventListener
    public void onPayoutPaid(WorkerPayoutPaidEvent event) {
        if (event == null || event.channel() != PaymentChannel.CASH || event.fromLedger()) {
            return;
        }
        if (event.amount() == null || event.amount().signum() <= 0) {
            log.warn("Cash payout {} has no positive amount; no cash entry recorded", event.payoutId());
            return;
        }
        if (cashLedgerService.findSystemEntry(SystemSource.PAYROLL_PAYOUT, event.payoutId()).isPresent()) {
            return;
        }
        LocalDate date = event.paidAt() == null
                ? LocalDate.now()
                : event.paidAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
        cashLedgerService.recordSystemEntry(new CashLedgerService.SystemEntry(
                SystemSource.PAYROLL_PAYOUT, event.payoutId(), null, event.currency(), event.amount(), date,
                event.workerName(), title(event),
                LinkRequest.of(LinkKind.WORKER_PAYOUT, event.amount()).withWorkerPayout(event.payoutId())));
    }

    /** The payout note, else its period ({@code from – to}). */
    private String title(WorkerPayoutPaidEvent event) {
        if (event.note() != null && !event.note().isBlank()) {
            return event.note().trim();
        }
        WorkerPayoutEntity payout = workerPayoutDao.findById(event.payoutId()).orElse(null);
        if (payout == null || payout.getPeriodFrom() == null || payout.getPeriodTo() == null) {
            return null;
        }
        return payout.getPeriodFrom() + " – " + payout.getPeriodTo();
    }
}
