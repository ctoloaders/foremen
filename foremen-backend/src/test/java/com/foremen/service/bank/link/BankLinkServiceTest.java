package com.foremen.service.bank.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.model.BankCounterpartyRuleEntity;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.BankTransactionSuggestionEntity;
import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.SuggestionStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.detect.MatchCandidate;
import com.foremen.service.payment.dto.BankTransferInput;
import com.foremen.service.payroll.dto.CreatePayoutRequest;

/**
 * Unit tests for {@link BankLinkService} / {@link LinkEffects} / {@link CounterpartyRuleService}
 * (FOR-07-04 task 6.7): validations, effects per kind on bank and cash lines, locked links, overrides,
 * suggestions, ignore and rule learning.
 *
 * <p><b>Validates: Requirements 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7, 16.1, 16.2, 16.4, 16.6, 16.7,
 * 16.8, 16.9, 16.10, 17.1, 17.3, 17.4, 17.5</b>
 */
class BankLinkServiceTest {

    private LinkServiceFixture f;
    private BankLinkService service;

    @BeforeEach
    void setUp() {
        f = new LinkServiceFixture();
        service = f.service;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("7", null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static BigDecimal pln(String v) {
        return new BigDecimal(v);
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (ForemenApiException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected ForemenApiException");
    }

    // --- Validations ----------------------------------------------------------------------------

    @Test
    void kindMustFitDirection() {
        BankTransactionEntity income = f.bankLine("500.00");
        WorkerPayoutEntity payout = f.payout(pln("500.00"), PayoutStatus.PLANNED);
        assertThat(code(() -> service.createLink(income.getId(),
                LinkRequest.of(LinkKind.WORKER_PAYOUT, null).withWorkerPayout(payout.getId()), false)))
                .isEqualTo(BankLinkService.KIND_DIRECTION);

        BankTransactionEntity expense = f.cashLine("-50.00");
        assertThat(code(() -> service.createLink(expense.getId(),
                LinkRequest.of(LinkKind.CLIENT_PAYMENT, null).withPaymentSchedule(f.schedule().getId()), false)))
                .isEqualTo(BankLinkService.KIND_DIRECTION);
        assertThat(f.links).isEmpty();
    }

    @Test
    void requiredTargetsPerKind() {
        BankTransactionEntity expense = f.bankLine("-100.00");
        for (LinkKind kind : List.of(LinkKind.WORKER_PAYOUT, LinkKind.PROCUREMENT, LinkKind.OTHER_EXPENSE)) {
            assertThat(code(() -> service.createLink(expense.getId(), LinkRequest.of(kind, null), false)))
                    .as(kind.name()).isEqualTo(BankLinkService.TARGET_REQUIRED);
        }
        BankTransactionEntity income = f.bankLine("100.00");
        assertThat(code(() -> service.createLink(income.getId(), LinkRequest.of(LinkKind.CLIENT_PAYMENT, null),
                false))).isEqualTo(BankLinkService.TARGET_REQUIRED);
        // OTHER_INCOME / INTERNAL_TRANSFER need no target.
        assertThat(service.createLink(income.getId(), LinkRequest.of(LinkKind.OTHER_INCOME, pln("60.00")), false)
                .linkStatus()).isEqualTo(LinkStatus.PARTIALLY_LINKED);
        assertThat(service.createLink(income.getId(), LinkRequest.of(LinkKind.INTERNAL_TRANSFER, null), false)
                .linkStatus()).isEqualTo(LinkStatus.LINKED);
        assertThat(f.linkedOnLine(income)).isEqualByComparingTo("100.00");
    }

    @Test
    void splitLinksAreBoundedByTheLineAmount() {
        BankTransactionEntity expense = f.bankLine("-300.00");
        ProcurementDocumentEntity a = f.document(pln("200.00"));
        ProcurementDocumentEntity b = f.document(pln("200.00"));
        service.createLink(expense.getId(),
                LinkRequest.of(LinkKind.PROCUREMENT, pln("200.00")).withProcurementDocument(a.getId()), false);
        assertThat(code(() -> service.createLink(expense.getId(),
                LinkRequest.of(LinkKind.PROCUREMENT, pln("150.00")).withProcurementDocument(b.getId()), false)))
                .isEqualTo(BankLinkService.OVER_ALLOCATED);
        LinkOutcome out = service.createLink(expense.getId(),
                LinkRequest.of(LinkKind.PROCUREMENT, null).withProcurementDocument(b.getId()), false);
        assertThat(out.linkStatus()).isEqualTo(LinkStatus.LINKED);
        assertThat(f.linksOf(expense)).extracting(BankTransactionLinkEntity::getAmount)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(pln("200.00"), pln("100.00"));
        assertThat(expense.getLinkStatus()).isEqualTo(LinkStatus.LINKED);
    }

    @Test
    void nonPositiveAmountRejected() {
        BankTransactionEntity expense = f.bankLine("-10.00");
        assertThat(code(() -> service.createLink(expense.getId(),
                LinkRequest.of(LinkKind.OTHER_EXPENSE, pln("0")).withUnplannedCategory(f.category().getId()), false)))
                .isEqualTo(BankLinkService.AMOUNT_INVALID);
    }

    // --- WORKER_PAYOUT effects ------------------------------------------------------------------

    @Test
    void plannedPayoutOnBankLineIsMarkedPaidAsBankTransfer() {
        BankTransactionEntity expense = f.bankLine("-1200.00");
        WorkerPayoutEntity payout = f.payout(pln("1200.00"), PayoutStatus.PLANNED);
        payout.setPaymentChannel(PaymentChannel.CASH);

        LinkOutcome out = service.createLink(expense.getId(),
                LinkRequest.of(LinkKind.WORKER_PAYOUT, null).withWorkerPayout(payout.getId()), true);

        verify(f.workerPayoutService).markPaidFromLedger(payout.getId(), LinkServiceFixture.BOOKING,
                PaymentChannel.BANK_TRANSFER);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(out.linkStatus()).isEqualTo(LinkStatus.LINKED);
        BankTransactionLinkEntity link = f.linksOf(expense).get(0);
        assertThat(link.getSource()).isEqualTo(DetectionSource.MANUAL);
        assertThat(link.getConfirmedBy()).isEqualTo("7");
        verify(f.auditLogDao, atLeastOnce()).save(any());
        // R17.3 — rule learned on a BANK_FEED line: counterparty → worker.
        BankCounterpartyRuleEntity rule = f.rules.get(expense.getCounterpartyKey());
        assertThat(rule.getTargetKind()).isEqualTo(LinkKind.WORKER_PAYOUT);
        assertThat(rule.getWorker()).isSameAs(payout.getWorker());
        assertThat(rule.getHits()).isEqualTo(1);
        assertThat(rule.getLastUsedAt()).isNotNull();
    }

    @Test
    void plannedPayoutOnCashLineIsMarkedPaidAsCashAndLearnsNothing() {
        BankTransactionEntity cash = f.cashLine("-800.00");
        cash.setCounterpartyKey("NAME:jan kowalski");
        WorkerPayoutEntity payout = f.payout(pln("800.00"), PayoutStatus.PLANNED);

        service.createLink(cash.getId(), LinkRequest.of(LinkKind.WORKER_PAYOUT, null).withWorkerPayout(payout.getId()),
                true);

        verify(f.workerPayoutService).markPaidFromLedger(payout.getId(), LinkServiceFixture.BOOKING,
                PaymentChannel.CASH);
        assertThat(payout.getPaymentChannel()).isEqualTo(PaymentChannel.CASH);
        assertThat(f.rules).isEmpty();
    }

    @Test
    void plannedPayoutRequiresTheExactAmount() {
        BankTransactionEntity expense = f.bankLine("-1500.00");
        WorkerPayoutEntity payout = f.payout(pln("1200.00"), PayoutStatus.PLANNED);
        assertThat(code(() -> service.createLink(expense.getId(),
                LinkRequest.of(LinkKind.WORKER_PAYOUT, pln("1000.00")).withWorkerPayout(payout.getId()), false)))
                .isEqualTo(BankLinkService.PAYOUT_AMOUNT_MISMATCH);
        verify(f.workerPayoutService, never()).markPaidFromLedger(anyLong(), any(), any());
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PLANNED);
    }

    @Test
    void paidPayoutOnlyRecordsTheLinkAndIsNotOverLinked() {
        WorkerPayoutEntity payout = f.payout(pln("1000.00"), PayoutStatus.PAID);
        BankTransactionEntity first = f.bankLine("-600.00");
        BankTransactionEntity second = f.bankLine("-600.00");
        service.createLink(first.getId(), LinkRequest.of(LinkKind.WORKER_PAYOUT, null).withWorkerPayout(payout.getId()),
                false);
        assertThat(code(() -> service.createLink(second.getId(),
                LinkRequest.of(LinkKind.WORKER_PAYOUT, pln("600.00")).withWorkerPayout(payout.getId()), false)))
                .isEqualTo(BankLinkService.TARGET_OVER_LINKED);
        // The default amount is capped by the payout remainder (400).
        LinkOutcome out = service.createLink(second.getId(),
                LinkRequest.of(LinkKind.WORKER_PAYOUT, null).withWorkerPayout(payout.getId()), false);
        assertThat(out.linkStatus()).isEqualTo(LinkStatus.PARTIALLY_LINKED);
        assertThat(f.linkedOnLine(second)).isEqualByComparingTo("400.00");
        verify(f.workerPayoutService, never()).markPaidFromLedger(anyLong(), any(), any());
    }

    @Test
    void removingAPayoutLinkKeepsThePayoutPaidAndWarns() {
        BankTransactionEntity expense = f.bankLine("-500.00");
        WorkerPayoutEntity payout = f.payout(pln("500.00"), PayoutStatus.PLANNED);
        LinkOutcome created = service.createLink(expense.getId(),
                LinkRequest.of(LinkKind.WORKER_PAYOUT, null).withWorkerPayout(payout.getId()), false);

        LinkOutcome removed = service.removeLink(created.linkId());

        assertThat(removed.payoutStaysPaid()).isTrue();
        assertThat(removed.linkStatus()).isEqualTo(LinkStatus.UNLINKED);
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(f.links).isEmpty();
    }

    @Test
    void createPayoutFromLineUsesTheLineChannelAndBookingMonth() {
        BankTransactionEntity cash = f.cashLine("-750.00");
        UserEntity worker = f.worker();

        LinkOutcome out = service.createPayoutFromLine(cash.getId(), worker.getId(), null, "zaliczka", false);

        ArgumentCaptor<CreatePayoutRequest> req = ArgumentCaptor.forClass(CreatePayoutRequest.class);
        verify(f.workerPayoutService).create(req.capture());
        assertThat(req.getValue().workerId()).isEqualTo(worker.getId());
        assertThat(req.getValue().payoutDate()).isEqualTo(LinkServiceFixture.BOOKING);
        assertThat(req.getValue().periodFrom()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(req.getValue().periodTo()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(req.getValue().paymentChannel()).isEqualTo(PaymentChannel.CASH);
        assertThat(req.getValue().amount()).isEqualByComparingTo("750.00");
        assertThat(req.getValue().note()).isEqualTo("bank-txn:" + cash.getId());
        verify(f.workerPayoutService).markPaidFromLedger(any(), eq(LinkServiceFixture.BOOKING), eq(PaymentChannel.CASH));
        assertThat(out.linkStatus()).isEqualTo(LinkStatus.LINKED);
        assertThat(f.linksOf(cash).get(0).getNote()).isEqualTo("zaliczka");
    }

    // --- CLIENT_PAYMENT effects + locked --------------------------------------------------------

    @Test
    void clientPaymentOnBankLineRecordsABankTransferAndLocksTheLink() {
        BankTransactionEntity income = f.bankLine("2500.00");
        PaymentScheduleEntity schedule = f.schedule();

        LinkOutcome out = service.createLink(income.getId(),
                LinkRequest.of(LinkKind.CLIENT_PAYMENT, null).withPaymentSchedule(schedule.getId()), false);

        assertThat(f.transferSchedules).containsExactly(schedule.getId());
        BankTransferInput in = f.transfers.get(0);
        assertThat(in.channel()).isEqualTo(com.foremen.service.payment.PaymentChannel.BANK_TRANSFER);
        assertThat(in.externalRef()).isEqualTo("bank-txn:" + income.getId() + ":" + out.linkId());
        assertThat(in.amount()).isEqualByComparingTo("2500.00");
        assertThat(in.bookingDate()).isEqualTo(LinkServiceFixture.BOOKING);
        BankTransactionLinkEntity link = f.linksOf(income).get(0);
        assertThat(link.getPaymentTransaction()).isNotNull();
        assertThat(link.getProject()).isSameAs(schedule.getProject());
        assertThat(service.hasLockedLinks(income.getId())).isTrue();

        assertThat(code(() -> service.removeLink(link.getId()))).isEqualTo(BankLinkService.LOCKED);
        assertThat(code(() -> service.replaceLink(link.getId(), LinkRequest.of(LinkKind.OTHER_INCOME, null), false)))
                .isEqualTo(BankLinkService.LOCKED);
        assertThat(code(() -> service.removeAllLinks(income.getId()))).isEqualTo(BankLinkService.LOCKED);
        assertThat(f.linksOf(income)).containsExactly(link);
    }

    @Test
    void clientPaymentOnCashLineUsesTheManualChannel() {
        BankTransactionEntity cash = f.cashLine("400.00");
        service.createLink(cash.getId(), LinkRequest.of(LinkKind.CLIENT_PAYMENT, null)
                .withPaymentSchedule(f.schedule().getId()), true);
        assertThat(f.transfers.get(0).channel()).isEqualTo(com.foremen.service.payment.PaymentChannel.MANUAL);
        assertThat(f.rules).isEmpty();
    }

    // --- PROCUREMENT / other kinds --------------------------------------------------------------

    @Test
    void procurementLinkHasNoWriteAndDrivesTheDerivedPaymentStatus() {
        ProcurementDocumentEntity doc = f.document(pln("300.00"));
        BankTransactionEntity first = f.bankLine("-100.00");
        BankTransactionEntity second = f.cashLine("-250.00");
        service.createLink(first.getId(), LinkRequest.of(LinkKind.PROCUREMENT, null)
                .withProcurementDocument(doc.getId()), true);
        assertThat(DocumentPaymentStatusDeriver.derive(doc.getDocumentTotal(), amountsOf(doc)))
                .isEqualTo(DocumentPaymentStatus.PARTIALLY_PAID);

        assertThat(code(() -> service.createLink(second.getId(), LinkRequest.of(LinkKind.PROCUREMENT, pln("250.00"))
                .withProcurementDocument(doc.getId()), false))).isEqualTo(BankLinkService.TARGET_OVER_LINKED);
        service.createLink(second.getId(), LinkRequest.of(LinkKind.PROCUREMENT, null)
                .withProcurementDocument(doc.getId()), false);
        assertThat(DocumentPaymentStatusDeriver.derive(doc.getDocumentTotal(), amountsOf(doc)))
                .isEqualTo(DocumentPaymentStatus.PAID);
        assertThat(doc.getDocumentTotal()).isEqualByComparingTo("300.00");
        verify(f.documentDao, never()).save(any());
        BankCounterpartyRuleEntity rule = f.rules.get(first.getCounterpartyKey());
        assertThat(rule.getSupplierNip()).isEqualTo("5260250995");
    }

    /** FOR-07-03 4.2 (R1.5, 11.2, 11.4): a company-level document (project == null) can be linked and learned from. */
    @Test
    void procurementLinkToCompanyDocumentWithoutProjectIsAccepted() {
        ProcurementDocumentEntity doc = f.document(pln("300.00"));
        doc.setProject(null);
        BankTransactionEntity line = f.bankLine("-100.00");

        service.createLink(line.getId(), LinkRequest.of(LinkKind.PROCUREMENT, null)
                .withProcurementDocument(doc.getId()), true);

        assertThat(DocumentPaymentStatusDeriver.derive(doc.getDocumentTotal(), amountsOf(doc)))
                .isEqualTo(DocumentPaymentStatus.PARTIALLY_PAID);
        assertThat(f.rules.get(line.getCounterpartyKey()).getSupplierNip()).isEqualTo("5260250995");
    }

    @Test
    void otherKindsHaveNoExternalEffects() {
        BankTransactionEntity expense = f.bankLine("-90.00");
        service.createLink(expense.getId(), LinkRequest.of(LinkKind.OTHER_EXPENSE, null)
                .withUnplannedCategory(f.category().getId()).withProject(f.project().getId()), false);
        verify(f.workerPayoutService, never()).markPaidFromLedger(anyLong(), any(), any());
        verify(f.paymentTransactionService, never()).recordBankTransfer(anyLong(), any());
        assertThat(f.linksOf(expense).get(0).getProject()).isNotNull();
    }

    // --- Auto / suggestions / override ----------------------------------------------------------

    @Test
    void applyAutoThenConfirmAuto() {
        BankTransactionEntity expense = f.bankLine("-200.00");
        long accountId = f.account(com.foremen.dao.model.BankAccountType.BANK).getId();
        MatchCandidate candidate = new MatchCandidate(LinkKind.INTERNAL_TRANSFER,
                new MatchCandidate.TargetRef(MatchCandidate.TargetType.OWN_ACCOUNT, accountId, null),
                new BigDecimal("1.00"), List.of("OWN_IBAN"));

        LinkOutcome auto = service.applyAuto(expense.getId(), candidate).orElseThrow();
        assertThat(auto.linkStatus()).isEqualTo(LinkStatus.AUTO_LINKED);
        BankTransactionLinkEntity link = f.linksOf(expense).get(0);
        assertThat(link.getSource()).isEqualTo(DetectionSource.RULE);
        assertThat(link.getConfirmedAt()).isNull();
        assertThat(link.getConfidence()).isEqualByComparingTo("1.000");
        assertThat(f.rules).isEmpty();

        LinkOutcome confirmed = service.confirmAuto(expense.getId(), true);
        assertThat(confirmed.linkStatus()).isEqualTo(LinkStatus.LINKED);
        assertThat(link.getConfirmedBy()).isEqualTo("7");
        assertThat(expense.getReviewedAt()).isNotNull();
        assertThat(f.rules.get(expense.getCounterpartyKey()).getCounterAccount().getId()).isEqualTo(accountId);
    }

    @Test
    void applyAutoSkipsAnInvalidCandidateWithoutWriting() {
        BankTransactionEntity expense = f.bankLine("-200.00");
        WorkerPayoutEntity payout = f.payout(pln("150.00"), PayoutStatus.PLANNED);
        MatchCandidate candidate = new MatchCandidate(LinkKind.WORKER_PAYOUT,
                new MatchCandidate.TargetRef(MatchCandidate.TargetType.WORKER_PAYOUT, payout.getId(), null),
                new BigDecimal("0.95"), List.of());
        // PLANNED payout default = its amount (150) ≤ line (200): applies as a partial link.
        assertThat(service.applyAuto(expense.getId(), candidate)).isPresent();
        // A second auto-apply for the same (now PAID, fully linked) payout is skipped.
        assertThat(service.applyAuto(expense.getId(), candidate)).isEmpty();
        assertThat(f.linksOf(expense)).hasSize(1);
    }

    @Test
    void suggestionsAreReplacedConfirmedAndRejected() {
        BankTransactionEntity expense = f.bankLine("-120.00");
        ProcurementDocumentEntity docA = f.document(pln("120.00"));
        ProcurementDocumentEntity docB = f.document(pln("120.00"));
        List<BankTransactionSuggestionEntity> first = service.replaceSuggestions(expense.getId(), List.of(
                new SuggestionDraft(LinkRequest.of(LinkKind.PROCUREMENT, null).withProcurementDocument(docA.getId()),
                        DetectionSource.RULE, new BigDecimal("0.8"), "amount, invoice")));
        assertThat(expense.getLinkStatus()).isEqualTo(LinkStatus.SUGGESTED);
        assertThat(first.get(0).getAmount()).isEqualByComparingTo("120.00");

        List<BankTransactionSuggestionEntity> second = service.replaceSuggestions(expense.getId(), List.of(
                new SuggestionDraft(LinkRequest.of(LinkKind.PROCUREMENT, null).withProcurementDocument(docA.getId()),
                        DetectionSource.RULE, new BigDecimal("0.8"), "a"),
                new SuggestionDraft(LinkRequest.of(LinkKind.PROCUREMENT, null).withProcurementDocument(docB.getId()),
                        DetectionSource.AI, new BigDecimal("0.6"), "b"),
                // Invalid for the direction → skipped.
                new SuggestionDraft(LinkRequest.of(LinkKind.OTHER_INCOME, null), DetectionSource.AI, null, "x")));
        assertThat(first.get(0).getStatus()).isEqualTo(SuggestionStatus.SUPERSEDED);
        assertThat(second).hasSize(2);
        assertThat(second).extracting(BankTransactionSuggestionEntity::getRank).containsExactly((short) 1, (short) 2);

        service.rejectSuggestion(second.get(0).getId());
        assertThat(second.get(0).getStatus()).isEqualTo(SuggestionStatus.REJECTED);
        assertThat(expense.getLinkStatus()).isEqualTo(LinkStatus.SUGGESTED);

        LinkOutcome out = service.confirmSuggestion(second.get(1).getId(), true);
        assertThat(out.linkStatus()).isEqualTo(LinkStatus.LINKED);
        BankTransactionLinkEntity link = f.linksOf(expense).get(0);
        assertThat(link.getSource()).isEqualTo(DetectionSource.AI);
        assertThat(link.getConfirmedAt()).isNotNull();
        assertThat(second.get(1).getStatus()).isEqualTo(SuggestionStatus.CONFIRMED);
        assertThat(f.rules).containsKey(expense.getCounterpartyKey());
        assertThat(code(() -> service.confirmSuggestion(second.get(1).getId(), false)))
                .isEqualTo(BankLinkService.SUGGESTION_NOT_OPEN);
    }

    @Test
    void replaceLinkOverridesInOneStepAndRollsNothingBackOnSuccess() {
        BankTransactionEntity expense = f.bankLine("-500.00");
        WorkerPayoutEntity payout = f.payout(pln("500.00"), PayoutStatus.PLANNED);
        LinkOutcome created = service.createLink(expense.getId(),
                LinkRequest.of(LinkKind.WORKER_PAYOUT, null).withWorkerPayout(payout.getId()), false);

        LinkOutcome replaced = service.replaceLink(created.linkId(),
                LinkRequest.of(LinkKind.OTHER_EXPENSE, null).withUnplannedCategory(f.category().getId()), true);

        assertThat(replaced.payoutStaysPaid()).isTrue();
        assertThat(f.linksOf(expense)).singleElement()
                .extracting(BankTransactionLinkEntity::getKind).isEqualTo(LinkKind.OTHER_EXPENSE);
        assertThat(replaced.linkStatus()).isEqualTo(LinkStatus.LINKED);
        assertThat(f.rules.get(expense.getCounterpartyKey()).getTargetKind()).isEqualTo(LinkKind.OTHER_EXPENSE);
    }

    // --- Ignore ---------------------------------------------------------------------------------

    @Test
    void ignoreRequiresAReasonAndNoLinks() {
        BankTransactionEntity expense = f.bankLine("-40.00");
        assertThat(code(() -> service.ignore(expense.getId(), " "))).isEqualTo(BankLinkService.IGNORE_REASON_REQUIRED);

        assertThat(service.ignore(expense.getId(), "prywatna karta").linkStatus()).isEqualTo(LinkStatus.IGNORED);
        assertThat(expense.getIgnoredBy()).isEqualTo("7");
        assertThat(code(() -> service.createLink(expense.getId(), LinkRequest.of(LinkKind.INTERNAL_TRANSFER, null),
                false))).isEqualTo(BankLinkService.TRANSACTION_IGNORED);

        assertThat(service.unignore(expense.getId()).linkStatus()).isEqualTo(LinkStatus.UNLINKED);
        service.createLink(expense.getId(), LinkRequest.of(LinkKind.INTERNAL_TRANSFER, null), false);
        assertThat(code(() -> service.ignore(expense.getId(), "x"))).isEqualTo(BankLinkService.IGNORE_HAS_LINKS);
    }

    // --- Counterparty rule CRUD -----------------------------------------------------------------

    @Test
    void ruleCrudValidatesTargetsAndKeys() {
        UserEntity worker = f.worker();
        CounterpartyRuleDto created = f.ruleService.create(new CounterpartyRuleRequest("NAME:jan kowalski", "Jan",
                LinkKind.WORKER_PAYOUT, worker.getId(), null, null, null, null, null, null, null));
        assertThat(created.workerId()).isEqualTo(worker.getId());
        assertThat(created.enabled()).isTrue();
        assertThat(created.origin()).isEqualTo(com.foremen.dao.model.RuleOrigin.MANUAL);

        assertThatThrownBy(() -> f.ruleService.create(new CounterpartyRuleRequest("NAME:jan kowalski", null,
                LinkKind.OTHER_INCOME, null, null, null, null, null, null, null, null)))
                .hasMessage(CounterpartyRuleService.DUPLICATE_KEY);
        assertThatThrownBy(() -> f.ruleService.create(new CounterpartyRuleRequest("NAME:x", null,
                LinkKind.PROCUREMENT, null, null, null, null, null, null, null, null)))
                .hasMessage(BankLinkService.TARGET_REQUIRED);
    }

    private List<BigDecimal> amountsOf(ProcurementDocumentEntity doc) {
        return f.links.stream().filter(l -> l.getProcurementDocument() == doc)
                .map(BankTransactionLinkEntity::getAmount).toList();
    }
}
