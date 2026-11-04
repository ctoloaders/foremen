package com.foremen.service.bank.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.BankTransactionSuggestionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.service.bank.detect.DetectionCandidateProviders.AiCatalog;
import com.foremen.service.bank.detect.DetectionService.DetectionSummary;
import com.foremen.service.bank.detect.MatchCandidate.TargetType;
import com.foremen.service.bank.detect.MatchInputs.DocumentCandidate;
import com.foremen.service.bank.detect.MatchInputs.OwnAccount;
import com.foremen.service.bank.detect.MatchInputs.PayoutCandidate;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.bank.link.LinkOutcome;
import com.foremen.service.bank.link.SuggestionDraft;

/**
 * FOR-07-04 task 6.8 — {@link DetectionService} rule step, AI step and re-run with mocked collaborators
 * (the matcher is real). The integration path is covered by {@code DetectionServiceIntegrationTest}.
 *
 * <p>Validates: Requirements 14.1, 14.2, 14.5, 14.6, 14.7, 15.2, 15.5, 15.6, 17.6, 27.7
 */
@Tag("Feature: FOR-07-04-bank-ledger, task 6.8: detection")
class DetectionServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private static final String OWN_IBAN = "PL61109010140000071219812874";

    private BankTransactionDao transactionDao;
    private BankTransactionLinkDao linkDao;
    private DetectionCandidateProviders providers;
    private BankLinkService linkService;
    private BankTransactionClassifier classifier;
    private final List<Runnable> executed = new ArrayList<>();
    private final Map<Long, BankTransactionEntity> lines = new HashMap<>();
    private CandidatePool pool = CandidatePool.empty();

    @BeforeEach
    void setUp() {
        transactionDao = mock(BankTransactionDao.class);
        linkDao = mock(BankTransactionLinkDao.class);
        providers = mock(DetectionCandidateProviders.class);
        linkService = mock(BankLinkService.class);
        classifier = mock(BankTransactionClassifier.class);
        when(classifier.isEnabled()).thenReturn(false);
        when(transactionDao.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(lines.get(i.<Long>getArgument(0))));
        when(transactionDao.findAllById(any())).thenAnswer(i -> {
            List<BankTransactionEntity> out = new ArrayList<>();
            for (Long id : i.<Iterable<Long>>getArgument(0)) {
                if (lines.containsKey(id)) {
                    out.add(lines.get(id));
                }
            }
            return out;
        });
        when(linkDao.findByTransactionIdOrderByIdAsc(anyLong())).thenReturn(List.of());
        when(providers.load(any())).thenAnswer(i -> pool);
        when(providers.counterpartyRules(anyString())).thenReturn(List.of());
        when(providers.aiCatalog()).thenReturn(new AiCatalog(
                List.of(new ClassificationInput.Category("FUEL", "Paliwo", "Топливо")), Map.of("FUEL", 7L),
                List.of(new ClassificationInput.Project(3L, "Dom", "Kraków"))));
        when(linkService.replaceSuggestions(anyLong(), anyList()))
                .thenAnswer(i -> i.<List<?>>getArgument(1).stream().map(d -> new BankTransactionSuggestionEntity()).toList());
        when(linkService.appendSuggestions(anyLong(), anyList()))
                .thenAnswer(i -> List.of(new BankTransactionSuggestionEntity()));
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private DetectionService service(int maxPerRun) {
        Executor executor = r -> {
            executed.add(r);
            r.run();
        };
        BankAiProperties ai = new BankAiProperties(true, "m", null, null, null, null, maxPerRun);
        return new DetectionService(transactionDao, linkDao, providers, linkService, classifier, ai,
                mock(PlatformTransactionManager.class), executor, MatchThresholds.DEFAULTS);
    }

    private BankTransactionEntity line(long id, String amount, String title, String counterparty, String iban) {
        BankConnectionEntity c = new BankConnectionEntity();
        c.setBankName("Test Bank");
        BankAccountEntity a = new BankAccountEntity();
        a.setId(100L);
        a.setConnection(c);
        BankTransactionEntity t = new BankTransactionEntity();
        t.setId(id);
        t.setAccount(a);
        t.setChannel(LedgerChannel.BANK);
        t.setOrigin(LedgerOrigin.BANK_FEED);
        t.setStatus(BankTxnStatus.POSTED);
        t.setAmount(new BigDecimal(amount));
        t.setCurrency("PLN");
        t.setOperationDate(DAY);
        t.setBookingDate(DAY);
        t.setTitle(title);
        t.setCounterpartyName(counterparty);
        t.setCounterpartyIban(iban);
        t.setCounterpartyKey(counterparty == null ? null : "NAME:" + counterparty.toUpperCase());
        lines.put(id, t);
        return t;
    }

    private static DocumentCandidate document(long id, String total, String invoice) {
        return new DocumentCandidate(id, new BigDecimal(total), invoice, "Castorama", null, DAY, DAY,
                DocumentPaymentStatus.UNMATCHED);
    }

    private static PayoutCandidate payout(long id, String amount, String worker) {
        return new PayoutCandidate(id, 50L + id, worker, new BigDecimal(amount), DAY, PayoutStatus.PLANNED,
                PaymentChannel.BANK_TRANSFER, false);
    }

    private static LinkOutcome outcome(long txnId, LinkStatus status) {
        return new LinkOutcome(txnId, 900L + txnId, status, false);
    }

    // --- scope ----------------------------------------------------------------------------------

    @Test
    @DisplayName("R14.1 / R27.7: cash, PENDING, ignored and already linked lines are not detected")
    void onlyPostedUnlinkedBankFeedLines() {
        BankTransactionEntity cash = line(1, "-10.00", "x", "A", null);
        cash.setChannel(LedgerChannel.CASH);
        cash.setOrigin(LedgerOrigin.MANUAL);
        line(2, "-10.00", "x", "B", null).setStatus(BankTxnStatus.PENDING);
        line(3, "-10.00", "x", "C", null).setIgnoredReason("private");
        line(4, "-10.00", "x", "D", null);
        when(linkDao.findByTransactionIdOrderByIdAsc(4L)).thenReturn(List.of(new BankTransactionLinkEntity()));
        pool = new CandidatePool(List.of(new OwnAccount(9L, OWN_IBAN)), null, null, null, null);

        DetectionSummary s = service(10).detect(List.of(1L, 2L, 3L, 4L, 99L), 7L);

        assertThat(s.processed()).isZero();
        assertThat(s.skipped()).isEqualTo(5);
        verify(linkService, never()).applyAuto(anyLong(), any());
        verify(linkService, never()).replaceSuggestions(anyLong(), anyList());
    }

    // --- rule step ------------------------------------------------------------------------------

    @Test
    @DisplayName("R14.2: an own-account IBAN is auto-linked as INTERNAL_TRANSFER; AUTO_LINKED counts as to review")
    void internalTransferAutoApplied() {
        line(1, "-500.00", "przelew wlasny", "Firma", OWN_IBAN);
        pool = new CandidatePool(List.of(new OwnAccount(9L, OWN_IBAN)), null, null, null, null);
        when(linkService.applyAuto(eq(1L), any())).thenReturn(Optional.of(outcome(1, LinkStatus.AUTO_LINKED)));
        when(classifier.isEnabled()).thenReturn(true);

        DetectionSummary s = service(10).detect(List.of(1L), 1L);

        ArgumentCaptor<MatchCandidate> c = ArgumentCaptor.forClass(MatchCandidate.class);
        verify(linkService).applyAuto(eq(1L), c.capture());
        assertThat(c.getValue().kind()).isEqualTo(LinkKind.INTERNAL_TRANSFER);
        assertThat(c.getValue().targetRef().id()).isEqualTo(9L);
        assertThat(s).isEqualTo(new DetectionSummary(1, 1, 0, 0, 1, 0));
        verify(classifier, never()).classify(any());
    }

    @Test
    @DisplayName("R14.6 / R14.7: a mid-score document becomes a RULE suggestion, not a link")
    void suggestionBelowAutoThreshold() {
        line(1, "-123.45", "zakupy", "Someone else", null);
        pool = new CandidatePool(null, null, null, List.of(document(5, "123.45", "FV/9/2026")), null);

        DetectionSummary s = service(10).detect(List.of(1L), 1L);

        verify(linkService, never()).applyAuto(anyLong(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SuggestionDraft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(linkService).replaceSuggestions(eq(1L), drafts.capture());
        assertThat(drafts.getValue()).singleElement().satisfies(d -> {
            assertThat(d.source()).isEqualTo(DetectionSource.RULE);
            assertThat(d.target().kind()).isEqualTo(LinkKind.PROCUREMENT);
            assertThat(d.target().procurementDocumentId()).isEqualTo(5L);
            assertThat(d.confidence()).isEqualByComparingTo("0.60");
        });
        assertThat(s.suggested()).isEqualTo(1);
        assertThat(s.toReview()).isEqualTo(1);
    }

    @Test
    @DisplayName("A rejected auto-apply falls back to the remaining candidates as suggestions")
    void autoApplyFallbackToSuggestions() {
        line(1, "-200.00", "FV/1/2026", "Castorama", null);
        // Document 5 scores 1.00 (auto); document 6 scores 0.80 (amount + supplier + date) → margin 0.20.
        pool = new CandidatePool(null, null, null,
                List.of(document(5, "200.00", "FV/1/2026"), document(6, "200.00", "ZZZ")), null);
        when(linkService.applyAuto(eq(1L), any())).thenReturn(Optional.empty());

        DetectionSummary s = service(10).detect(List.of(1L), 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SuggestionDraft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(linkService).replaceSuggestions(eq(1L), drafts.capture());
        assertThat(drafts.getValue()).extracting(d -> d.target().procurementDocumentId()).containsExactly(6L);
        assertThat(s.autoLinked()).isZero();
        assertThat(s.suggested()).isEqualTo(1);
    }

    @Test
    @DisplayName("R14.5: a payout auto-linked by one line is not auto-linked again by the next line of the batch")
    void consumedTargetsAreExcludedWithinBatch() {
        line(1, "-800.00", "wyplata", "Jan Kowalski", null);
        line(2, "-800.00", "wyplata", "Jan Kowalski", null);
        pool = new CandidatePool(null, null, List.of(payout(11, "800.00", "Jan Kowalski")), null, null);
        when(linkService.applyAuto(anyLong(), any()))
                .thenAnswer(i -> Optional.of(outcome(i.getArgument(0), LinkStatus.AUTO_LINKED)));

        DetectionSummary s = service(10).detect(List.of(1L, 2L), 1L);

        verify(linkService, times(1)).applyAuto(anyLong(), any());
        verify(linkService).applyAuto(eq(1L), any());
        assertThat(s.autoLinked()).isEqualTo(1);
        assertThat(s.processed()).isEqualTo(2);
    }

    @Test
    @DisplayName("A failing line is logged and does not stop the batch")
    void failingLineIsIsolated() {
        line(1, "-500.00", "a", "Firma", OWN_IBAN);
        line(2, "-500.00", "b", "Firma", OWN_IBAN);
        pool = new CandidatePool(List.of(new OwnAccount(9L, OWN_IBAN)), null, null, null, null);
        when(linkService.applyAuto(eq(1L), any())).thenThrow(new IllegalStateException("boom"));
        when(linkService.applyAuto(eq(2L), any())).thenReturn(Optional.of(outcome(2, LinkStatus.AUTO_LINKED)));

        DetectionSummary s = service(10).detect(List.of(1L, 2L), 1L);

        assertThat(s.processed()).isEqualTo(2);
        assertThat(s.autoLinked()).isEqualTo(1);
        assertThat(s.toReview()).isEqualTo(2);
    }

    // --- AI step --------------------------------------------------------------------------------

    @Test
    @DisplayName("R15.5 / R15.6: AI runs once per line, capped by max-per-run, stored as an AI suggestion")
    void aiStepCappedAndStoredAsSuggestion() {
        line(1, "-50.00", "ORLEN stacja", "ORLEN", "PL00123456789012345678901234");
        line(2, "-60.00", "ORLEN stacja", "ORLEN", null);
        when(classifier.isEnabled()).thenReturn(true);
        when(classifier.classify(any())).thenReturn(Optional.of(
                new ClassifierAnswer("OTHER_EXPENSE", null, "FUEL", 3L, 0.8, "paliwo")));

        DetectionSummary s = service(1).detect(List.of(1L, 2L), 1L);

        assertThat(s.aiQueued()).isEqualTo(1);
        ArgumentCaptor<ClassificationInput> input = ArgumentCaptor.forClass(ClassificationInput.class);
        verify(classifier, times(1)).classify(input.capture());
        assertThat(input.getValue().direction()).isEqualTo("EXPENSE");
        assertThat(input.getValue().counterpartyIbanLast4()).isEqualTo("1234");
        assertThat(input.getValue().bankName()).isEqualTo("Test Bank");
        assertThat(input.getValue().categories()).hasSize(1);
        assertThat(lines.get(1L).getAiClassifiedAt()).isNotNull();
        assertThat(lines.get(2L).getAiClassifiedAt()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SuggestionDraft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(linkService).appendSuggestions(eq(1L), drafts.capture());
        assertThat(drafts.getValue()).singleElement().satisfies(d -> {
            assertThat(d.source()).isEqualTo(DetectionSource.AI);
            assertThat(d.target().kind()).isEqualTo(LinkKind.OTHER_EXPENSE);
            assertThat(d.target().unplannedCategoryId()).isEqualTo(7L);
            assertThat(d.target().projectId()).isEqualTo(3L);
            assertThat(d.confidence()).isEqualByComparingTo("0.80");
        });
        verify(linkService, never()).applyAuto(anyLong(), any());

        // Second detection of the same line: already classified → no further AI call.
        service(10).detect(List.of(1L), 2L);
        verify(classifier, times(1)).classify(any());
    }

    @Test
    @DisplayName("R15.7: no AI work is queued while the classifier is disabled")
    void noAiWhenDisabled() {
        line(1, "-50.00", "ORLEN", "ORLEN", null);
        DetectionSummary s = service(10).detect(List.of(1L), 1L);
        assertThat(s.aiQueued()).isZero();
        assertThat(executed).isEmpty();
        verify(classifier, never()).classify(any());
    }

    @Test
    @DisplayName("R15.6: inside a transaction the AI step is deferred until after commit")
    void aiDeferredAfterCommit() {
        line(1, "-50.00", "ORLEN", "ORLEN", null);
        when(classifier.isEnabled()).thenReturn(true);
        when(classifier.classify(any())).thenReturn(Optional.empty());
        TransactionSynchronizationManager.initSynchronization();

        service(10).detect(List.of(1L), 1L);
        assertThat(executed).isEmpty();
        verify(classifier, never()).classify(any());

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);
        assertThat(executed).hasSize(1);
        verify(classifier).classify(any());
        // Failed / empty answer: the line is still marked classified and gets no suggestion.
        assertThat(lines.get(1L).getAiClassifiedAt()).isNotNull();
        verify(linkService, never()).appendSuggestions(anyLong(), anyList());
    }

    @Test
    @DisplayName("AI answers map onto a supplied candidate or the kind's own target; target-less kinds are dropped")
    void toDraftMapping() {
        MatchCandidate payout = new MatchCandidate(LinkKind.WORKER_PAYOUT,
                new MatchCandidate.TargetRef(TargetType.WORKER_PAYOUT, 11L, null), new BigDecimal("0.40"), List.of());
        AiCatalog catalog = new AiCatalog(List.of(), Map.of("FUEL", 7L), List.of());

        assertThat(DetectionService.toDraft(new ClassifierAnswer("WORKER_PAYOUT", "WORKER_PAYOUT:11", null, null,
                0.7, "r"), List.of(payout), catalog)).get()
                .satisfies(d -> assertThat(d.target().workerPayoutId()).isEqualTo(11L));
        assertThat(DetectionService.toDraft(new ClassifierAnswer("WORKER_PAYOUT", null, null, null, 0.7, "r"),
                List.of(payout), catalog)).isEmpty();
        assertThat(DetectionService.toDraft(new ClassifierAnswer("OTHER_EXPENSE", null, null, null, 0.7, "r"),
                List.of(), catalog)).isEmpty();
        assertThat(DetectionService.toDraft(new ClassifierAnswer("INTERNAL_TRANSFER", null, null, null, 1.7, "r"),
                List.of(), catalog)).get()
                .satisfies(d -> assertThat(d.confidence()).isEqualByComparingTo("1.00"));
        assertThat(DetectionService.toDraft(new ClassifierAnswer(null, null, null, null, 0.7, "r"),
                List.of(), catalog)).isEmpty();
    }

    // --- re-run ---------------------------------------------------------------------------------

    @Test
    @DisplayName("R17.6: re-run clears open suggestions, re-runs rules and forces AI when requested")
    void rerunClearsAndForcesAi() {
        BankTransactionEntity t = line(1, "-123.45", "zakupy", "X", null);
        t.setLinkStatus(LinkStatus.SUGGESTED);
        t.setAiClassifiedAt(OffsetDateTime.now().minusDays(1));
        pool = new CandidatePool(null, null, null, List.of(document(5, "123.45", "FV/9/2026")), null);
        when(classifier.isEnabled()).thenReturn(true);
        when(classifier.classify(any())).thenReturn(Optional.empty());

        DetectionService service = service(10);
        DetectionSummary withoutAi = service.rerunDetection(1L, false);
        verify(linkService).clearOpenSuggestions(1L);
        verify(linkService).replaceSuggestions(eq(1L), anyList());
        assertThat(withoutAi.aiQueued()).isZero();

        DetectionSummary withAi = service.rerunDetection(1L, true);
        assertThat(withAi.aiQueued()).isEqualTo(1);
        verify(classifier).classify(any());
    }

    @Test
    @DisplayName("R17.6: re-run skips lines that carry links")
    void rerunSkipsLinkedLines() {
        line(1, "-123.45", "zakupy", "X", null).setLinkStatus(LinkStatus.AUTO_LINKED);
        when(linkDao.findByTransactionIdOrderByIdAsc(1L)).thenReturn(List.of(new BankTransactionLinkEntity()));

        DetectionSummary s = service(10).rerunDetection(1L, true);

        assertThat(s.skipped()).isEqualTo(1);
        verify(linkService, never()).clearOpenSuggestions(anyLong());
    }
}
