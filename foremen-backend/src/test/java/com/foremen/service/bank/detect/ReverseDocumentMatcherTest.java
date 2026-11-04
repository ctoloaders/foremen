package com.foremen.service.bank.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.BankTransactionSuggestionDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionSuggestionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.SuggestionStatus;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.bank.link.LinkOutcome;
import com.foremen.service.bank.link.SuggestionDraft;
import com.foremen.service.procurement.ProcurementDocumentParsedEvent;

/**
 * FOR-07-04 task 6.8 — {@link ReverseDocumentMatcher} with mocked DAOs (the scorer is real).
 *
 * <p>Validates: Requirements 28.2, 28.3
 */
@Tag("Feature: FOR-07-04-bank-ledger, task 6.8: reverse document matching")
class ReverseDocumentMatcherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private static final long DOC_ID = 5L;

    private ProcurementDocumentDao documentDao;
    private BankTransactionDao transactionDao;
    private BankTransactionLinkDao linkDao;
    private BankTransactionSuggestionDao suggestionDao;
    private DetectionCandidateProviders providers;
    private BankLinkService linkService;
    private final List<Runnable> executed = new ArrayList<>();
    private ReverseDocumentMatcher matcher;

    @BeforeEach
    void setUp() {
        documentDao = mock(ProcurementDocumentDao.class);
        transactionDao = mock(BankTransactionDao.class);
        linkDao = mock(BankTransactionLinkDao.class);
        suggestionDao = mock(BankTransactionSuggestionDao.class);
        providers = mock(DetectionCandidateProviders.class);
        linkService = mock(BankLinkService.class);
        when(providers.counterpartyRules(anyString())).thenReturn(List.of());
        when(linkDao.sumAmountByProcurementDocumentId(anyLong())).thenReturn(BigDecimal.ZERO);
        when(suggestionDao.findByTransactionIdAndStatusOrderByRankAsc(anyLong(), eq(SuggestionStatus.OPEN)))
                .thenReturn(List.of());
        when(linkService.appendSuggestions(anyLong(), anyList()))
                .thenAnswer(i -> List.of(new BankTransactionSuggestionEntity()));
        matcher = new ReverseDocumentMatcher(documentDao, transactionDao, linkDao, suggestionDao, providers,
                linkService, mock(PlatformTransactionManager.class), executed::add, MatchThresholds.DEFAULTS);
    }

    private ProcurementDocumentEntity document(String total, String invoice) {
        ProcurementDocumentEntity d = new ProcurementDocumentEntity();
        d.setId(DOC_ID);
        d.setDocumentTotal(new BigDecimal(total));
        d.setInvoiceNumber(invoice);
        d.setSupplierName("Castorama");
        d.setInvoiceDate(DAY);
        d.setUploadedAt(OffsetDateTime.of(2026, 3, 11, 9, 0, 0, 0, ZoneOffset.UTC));
        when(documentDao.findById(DOC_ID)).thenReturn(Optional.of(d));
        return d;
    }

    private static BankTransactionEntity line(long id, String amount, String title, String counterparty) {
        BankTransactionEntity t = new BankTransactionEntity();
        t.setId(id);
        t.setChannel(LedgerChannel.BANK);
        t.setOrigin(LedgerOrigin.BANK_FEED);
        t.setStatus(BankTxnStatus.POSTED);
        t.setLinkStatus(LinkStatus.UNLINKED);
        t.setAmount(new BigDecimal(amount));
        t.setCurrency("PLN");
        t.setBookingDate(DAY);
        t.setTitle(title);
        t.setCounterpartyName(counterparty);
        t.setCounterpartyKey("NAME:" + counterparty.toUpperCase());
        return t;
    }

    private void lines(BankTransactionEntity... ls) {
        when(transactionDao.findExpenseLinesForDocumentMatching(any(), any(), any())).thenReturn(List.of(ls));
    }

    @Test
    @DisplayName("R28.2: a full-score line (amount + invoice no. + supplier + date) is auto-linked")
    void autoLinksBestLine() {
        document("200.00", "FV/1/2026");
        lines(line(1, "-200.00", "Zaplata FV/1/2026", "Castorama"), line(2, "-57.00", "inne", "Ktos"));
        when(linkService.applyAuto(eq(1L), any())).thenReturn(Optional.of(new LinkOutcome(1L, 900L,
                LinkStatus.AUTO_LINKED, false)));

        ReverseDocumentMatcher.Result r = matcher.matchDocument(DOC_ID);

        assertThat(r).isEqualTo(new ReverseDocumentMatcher.Result(ReverseDocumentMatcher.Outcome.AUTO_LINKED, 1L));
        ArgumentCaptor<MatchCandidate> c = ArgumentCaptor.forClass(MatchCandidate.class);
        verify(linkService).applyAuto(eq(1L), c.capture());
        assertThat(c.getValue().kind()).isEqualTo(LinkKind.PROCUREMENT);
        assertThat(c.getValue().targetRef().id()).isEqualTo(DOC_ID);
        verify(linkService, never()).appendSuggestions(anyLong(), anyList());
    }

    @Test
    @DisplayName("R28.3: a mid-score line gets one PROCUREMENT suggestion, never a link")
    void suggestsBelowAutoThreshold() {
        document("123.45", "FV/9/2026");
        lines(line(1, "-123.45", "zakupy", "Someone else"));

        ReverseDocumentMatcher.Result r = matcher.matchDocument(DOC_ID);

        assertThat(r.outcome()).isEqualTo(ReverseDocumentMatcher.Outcome.SUGGESTED);
        verify(linkService, never()).applyAuto(anyLong(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SuggestionDraft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(linkService).appendSuggestions(eq(1L), drafts.capture());
        assertThat(drafts.getValue()).singleElement()
                .satisfies(d -> assertThat(d.target().procurementDocumentId()).isEqualTo(DOC_ID));
    }

    @Test
    @DisplayName("An already suggested document is not suggested twice")
    void noDuplicateSuggestion() {
        ProcurementDocumentEntity doc = document("123.45", "FV/9/2026");
        lines(line(1, "-123.45", "zakupy", "Someone else"));
        BankTransactionSuggestionEntity existing = new BankTransactionSuggestionEntity();
        existing.setProcurementDocument(doc);
        when(suggestionDao.findByTransactionIdAndStatusOrderByRankAsc(1L, SuggestionStatus.OPEN))
                .thenReturn(List.of(existing));

        assertThat(matcher.matchDocument(DOC_ID).outcome()).isEqualTo(ReverseDocumentMatcher.Outcome.SUGGESTED);
        verify(linkService, never()).appendSuggestions(anyLong(), anyList());
    }

    @Test
    @DisplayName("R28.2: a document that is already (partly) linked — e.g. LINKED_FROM_LEDGER / PAID_CASH — is skipped")
    void skipsNonUnmatchedDocuments() {
        document("200.00", "FV/1/2026");
        when(linkDao.sumAmountByProcurementDocumentId(DOC_ID)).thenReturn(new BigDecimal("50.00"));

        assertThat(matcher.matchDocument(DOC_ID).outcome()).isEqualTo(ReverseDocumentMatcher.Outcome.SKIPPED);
        verify(transactionDao, never()).findExpenseLinesForDocumentMatching(any(), any(), any());
    }

    @Test
    @DisplayName("No candidate line above the suggest threshold → NONE; unknown document → SKIPPED")
    void noneAndUnknown() {
        document("999.99", "FV/1/2026");
        lines(line(1, "-12.00", "kawa", "Kawiarnia"));
        assertThat(matcher.matchDocument(DOC_ID).outcome()).isEqualTo(ReverseDocumentMatcher.Outcome.NONE);
        assertThat(matcher.matchDocument(77L).outcome()).isEqualTo(ReverseDocumentMatcher.Outcome.SKIPPED);
        verify(linkService, never()).applyAuto(anyLong(), any());
        verify(linkService, never()).appendSuggestions(anyLong(), anyList());
    }

    @Test
    @DisplayName("The parsed event is handed to the executor; matching runs there")
    void onParsedDispatchesToExecutor() {
        document("123.45", "FV/9/2026");
        lines(line(1, "-123.45", "zakupy", "Someone else"));

        matcher.onParsed(new ProcurementDocumentParsedEvent(DOC_ID));
        verify(linkService, never()).appendSuggestions(anyLong(), anyList());
        assertThat(executed).hasSize(1);

        executed.get(0).run();
        verify(linkService).appendSuggestions(eq(1L), anyList());
    }
}
