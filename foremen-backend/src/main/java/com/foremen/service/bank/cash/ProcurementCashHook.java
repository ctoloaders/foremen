package com.foremen.service.bank.cash;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.SystemSource;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.link.LinkRequest;
import com.foremen.service.procurement.ProcurementCashPayment;
import com.foremen.service.procurement.ProcurementCashPaymentHandler;

/**
 * FOR-07-04 (Requirements 28.1, 28.4; design §Cash ledger, Property 11) — the cash-ledger implementation of
 * {@link ProcurementCashPaymentHandler}: upload {@code paymentChoice = PAID_CASH} and "Mark paid in cash"
 * create one SYSTEM cash expense entry ({@code PROCUREMENT_CASH}, documentId) linked {@code PROCUREMENT} to
 * the document, in the caller's transaction.
 *
 * <ul>
 *   <li>account: the given cash account (must not be archived), else the default PLN cash account
 *       (auto-created when missing);</li>
 *   <li>amount: the given amount, else the document total; both unknown → 400
 *       {@link CashLedgerService#AMOUNT_REQUIRED};</li>
 *   <li>date: the given date, else the invoice date, else today;</li>
 *   <li>a second payment for the same document → 409 {@link CashLedgerService#ALREADY_RECORDED}.</li>
 * </ul>
 * The link amount defaults to the line remainder capped by the known document total, so an amount above
 * the total leaves the entry partially linked rather than over-linking the document.
 */
@Component
public class ProcurementCashHook implements ProcurementCashPaymentHandler {

    private final CashLedgerService cashLedgerService;
    private final ProcurementDocumentDao documentDao;

    public ProcurementCashHook(CashLedgerService cashLedgerService, ProcurementDocumentDao documentDao) {
        this.cashLedgerService = cashLedgerService;
        this.documentDao = documentDao;
    }

    @Override
    @Transactional
    public void payInCash(ProcurementCashPayment payment) {
        ProcurementDocumentEntity document = documentDao.findById(payment.documentId())
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, CashLedgerService.NOT_FOUND));
        BigDecimal amount = payment.amount() != null ? payment.amount() : document.getDocumentTotal();
        if (amount == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, CashLedgerService.AMOUNT_REQUIRED);
        }
        LocalDate date = payment.date() != null ? payment.date()
                : document.getInvoiceDate() != null ? document.getInvoiceDate() : LocalDate.now();
        String title = document.getInvoiceNumber() != null && !document.getInvoiceNumber().isBlank()
                ? document.getInvoiceNumber() : null;
        cashLedgerService.recordSystemEntry(new CashLedgerService.SystemEntry(
                SystemSource.PROCUREMENT_CASH, document.getId(), payment.cashAccountId(),
                CashLedgerService.DEFAULT_CURRENCY, amount, date, document.getSupplierName(), title,
                LinkRequest.of(LinkKind.PROCUREMENT, null).withProcurementDocument(document.getId())));
    }
}
