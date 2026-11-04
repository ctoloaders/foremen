package com.foremen.service.bank.sync;

import static com.foremen.service.bank.sync.FakeBankFeedConnector.account;
import static com.foremen.service.bank.sync.FakeBankFeedConnector.page;
import static com.foremen.service.bank.sync.FakeBankFeedConnector.posted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SyncRunStatus;
import com.foremen.dao.model.SyncTrigger;
import com.foremen.service.bank.account.BankAccountService;
import com.foremen.service.bank.account.BankAccountService.AccountUpsert;
import com.foremen.service.bank.account.BankAccountService.UpsertResult;
import com.foremen.service.bank.connection.BankConnectionService;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.TransactionPage;
import com.foremen.service.bank.detect.DetectionService;
import com.foremen.service.bank.detect.DetectionService.DetectionSummary;
import com.foremen.service.bank.ingest.BalanceContinuityChecker;
import com.foremen.service.bank.ingest.BankTransactionIngestService;
import com.foremen.service.bank.ingest.IngestResult;
import com.foremen.service.bank.sync.SyncRunRecorder.ActiveRun;
import com.foremen.service.bank.sync.SyncRunRecorder.RunOutcome;
import com.foremen.service.bank.sync.SyncRunRecorder.RunStart;

/**
 * FOR-07-04 task 6.4 — {@link BankSyncOrchestrator} against a scripted {@link FakeBankFeedConnector}
 * (services and the run recorder mocked; the executor runs inline).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankSyncOrchestratorTest {

    private static final Long CONNECTION_ID = 7L;
    private static final Long RUN_ID = 100L;
    private static final Instant NOW = Instant.parse("2026-01-15T06:30:00Z");

    @Mock private SyncRunRecorder recorder;
    @Mock private BankConnectionDao connectionDao;
    @Mock private BankAccountDao accountDao;
    @Mock private BankConnectionService connectionService;
    @Mock private BankAccountService accountService;
    @Mock private BankTransactionIngestService ingestService;
    @Mock private DetectionService detectionService;

    private FakeBankFeedConnector connector;
    private BankConnectionEntity connection;
    private final List<Runnable> submitted = new ArrayList<>();
    private Executor executor;
    private BankSyncOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        connector = new FakeBankFeedConnector(FeedProvider.SALT_EDGE);
        connection = new BankConnectionEntity();
        connection.setId(CONNECTION_ID);
        connection.setProvider(FeedProvider.SALT_EDGE);
        connection.setExternalConnectionId("ext-1");
        connection.setStatus(BankConnectionStatus.ACTIVE);
        executor = Runnable::run;
        orchestrator = newOrchestrator(r -> {
            submitted.add(r);
            executor.execute(r);
        });

        when(connectionDao.findById(CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(connectionService.applyConnectionState(eq(CONNECTION_ID), any())).thenReturn(connection);
        when(recorder.markRunning(RUN_ID)).thenReturn(Optional.of(new RunStart(RUN_ID, CONNECTION_ID, SyncTrigger.MANUAL)));
        when(recorder.finish(eq(RUN_ID), any())).thenReturn(true);
        when(recorder.findActive(eq(CONNECTION_ID), any())).thenReturn(Optional.empty());
        when(detectionService.detect(anyList(), any())).thenReturn(DetectionSummary.empty());
    }

    private BankSyncOrchestrator newOrchestrator(Executor ex) {
        BankSyncOrchestrator o = new BankSyncOrchestrator(recorder, connectionDao, accountDao, connectionService,
                accountService, ingestService, detectionService, new BankFeedConnectorRegistry(List.of(connector)),
                ex, "30m");
        o.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        return o;
    }

    private BankAccountEntity bankAccount(Long id, String cursor) {
        BankAccountEntity a = new BankAccountEntity();
        a.setId(id);
        a.setType(BankAccountType.BANK);
        a.setCurrency("PLN");
        a.setTransactionsCursor(cursor);
        when(accountDao.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    private static AccountUpsert upsert(Long id, String externalId, boolean included) {
        return new AccountUpsert(id, externalId, false, included, null, null, null, null);
    }

    private void accounts(AccountUpsert... rows) {
        when(accountService.upsertAccounts(eq(CONNECTION_ID), any(), anyList()))
                .thenReturn(new UpsertResult(List.of(rows), List.of(), 0));
    }

    private static IngestResult ingested(int inserted, Long... ids) {
        return new IngestResult(inserted, 0, 1, 2, 0, 0, 1, 0, 0, List.of(ids));
    }

    private RunOutcome finishedOutcome() {
        ArgumentCaptor<RunOutcome> captor = ArgumentCaptor.forClass(RunOutcome.class);
        verify(recorder).finish(eq(RUN_ID), captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------ enqueue (R5.3, R5.4)

    @Test
    void enqueueInsertsQueuedRunAndSubmitsIt() {
        when(recorder.insertQueued(CONNECTION_ID, SyncTrigger.MANUAL, "alice")).thenReturn(Optional.of(RUN_ID));
        when(recorder.markRunning(RUN_ID)).thenReturn(Optional.empty());

        Optional<Long> runId = orchestrator.enqueue(CONNECTION_ID, SyncTrigger.MANUAL, "alice");

        assertThat(runId).contains(RUN_ID);
        assertThat(submitted).hasSize(1);
        verify(recorder).markRunning(RUN_ID);
    }

    @Test
    void enqueueCoalescesIntoRunningRunAndRunsOneFollowUpAfterIt() {
        when(recorder.findActive(eq(CONNECTION_ID), any()))
                .thenReturn(Optional.of(new ActiveRun(RUN_ID, SyncRunStatus.RUNNING, false)))
                .thenReturn(Optional.of(new ActiveRun(RUN_ID, SyncRunStatus.RUNNING, false)))
                .thenReturn(Optional.empty());
        when(recorder.insertQueued(CONNECTION_ID, SyncTrigger.CALLBACK, "SYSTEM")).thenReturn(Optional.of(101L));
        accounts();

        assertThat(orchestrator.enqueue(CONNECTION_ID, SyncTrigger.CALLBACK, null)).contains(RUN_ID);
        assertThat(orchestrator.enqueue(CONNECTION_ID, SyncTrigger.CALLBACK, null)).contains(RUN_ID);
        verify(recorder, never()).insertQueued(anyLong(), any(), anyString());

        orchestrator.run(RUN_ID); // the active run finishes → exactly one follow-up

        verify(recorder, times(1)).insertQueued(CONNECTION_ID, SyncTrigger.CALLBACK, "SYSTEM");
    }

    @Test
    void enqueueIntoQueuedRunNeedsNoFollowUp() {
        when(recorder.findActive(eq(CONNECTION_ID), any()))
                .thenReturn(Optional.of(new ActiveRun(RUN_ID, SyncRunStatus.QUEUED, false)))
                .thenReturn(Optional.empty());
        accounts();

        assertThat(orchestrator.enqueue(CONNECTION_ID, SyncTrigger.MANUAL, "bob")).contains(RUN_ID);
        orchestrator.run(RUN_ID);

        verify(recorder, never()).insertQueued(anyLong(), any(), anyString());
    }

    @Test
    void uniqueIndexRaceIsCoalescedIntoTheWinningRun() {
        when(recorder.findActive(eq(CONNECTION_ID), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ActiveRun(55L, SyncRunStatus.QUEUED, false)));
        when(recorder.insertQueued(CONNECTION_ID, SyncTrigger.SCHEDULE, "SYSTEM"))
                .thenThrow(new DataIntegrityViolationException("ux_bank_sync_runs_one_active"));

        assertThat(orchestrator.enqueue(CONNECTION_ID, SyncTrigger.SCHEDULE, "SYSTEM")).contains(55L);
        assertThat(submitted).isEmpty();
    }

    @Test
    void staleActiveRunIsFailedAndANewRunQueued() {
        when(recorder.findActive(eq(CONNECTION_ID), any()))
                .thenReturn(Optional.of(new ActiveRun(55L, SyncRunStatus.RUNNING, true)))
                .thenReturn(Optional.empty());
        when(recorder.insertQueued(CONNECTION_ID, SyncTrigger.MANUAL, "alice")).thenReturn(Optional.of(RUN_ID));
        when(recorder.markRunning(RUN_ID)).thenReturn(Optional.empty());

        assertThat(orchestrator.enqueue(CONNECTION_ID, SyncTrigger.MANUAL, "alice")).contains(RUN_ID);
        verify(recorder).failActive(eq(55L), eq(SyncRunRecorder.STALE), anyString());
    }

    @Test
    void rejectedSubmissionFailsTheQueuedRun() {
        BankSyncOrchestrator rejecting = newOrchestrator(r -> {
            throw new RejectedExecutionException("full");
        });
        when(recorder.insertQueued(CONNECTION_ID, SyncTrigger.MANUAL, "alice")).thenReturn(Optional.of(RUN_ID));

        assertThat(rejecting.enqueue(CONNECTION_ID, SyncTrigger.MANUAL, "alice")).contains(RUN_ID);
        verify(recorder).failActive(eq(RUN_ID), eq(BankSyncOrchestrator.REJECTED), anyString());
    }

    // ------------------------------------------------------------------ run steps (R5.2)

    @Test
    void runFetchesAllPagesIngestsDetectsAndFinishesSucceeded() {
        BankAccountEntity included = bankAccount(1L, "40");
        bankAccount(2L, null);
        accounts(upsert(1L, "acc-1", true), upsert(2L, "acc-2", false));
        TransactionPage p0 = page(List.of(posted("41", "-10.00"), posted("42", "-5.00")), "page:1");
        TransactionPage p1 = page(List.of(posted("45", "20.00")), null);
        connector.pages.put("acc-1", List.of(p0, p1));
        when(ingestService.ingestAll(included, List.of(p0, p1), RUN_ID)).thenReturn(ingested(3, 11L, 12L, 13L));
        when(detectionService.detect(List.of(11L, 12L, 13L), RUN_ID))
                .thenReturn(new DetectionSummary(3, 1, 1, 0, 2, 0));

        orchestrator.run(RUN_ID);

        assertThat(connector.fetchCalls).containsExactly("acc-1@40", "acc-1@page:1");
        verify(connectionService).applyConnectionState(CONNECTION_ID, connector.state);
        ArgumentCaptor<HolderInfo> holder = ArgumentCaptor.forClass(HolderInfo.class);
        verify(accountService).upsertAccounts(eq(CONNECTION_ID), holder.capture(), eq(connector.accounts));
        assertThat(holder.getValue()).isEqualTo(connector.holder);
        verify(recorder).storeCursor(1L, "45");
        verify(recorder).recordAccount(eq(RUN_ID), eq(1L), any(), eq(SyncRunStatus.SUCCEEDED), isNull());
        verify(recorder, never()).recordAccount(eq(RUN_ID), eq(2L), any(), any(), any());

        RunOutcome outcome = finishedOutcome();
        assertThat(outcome.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(outcome.accountsSeen()).isEqualTo(2);
        assertThat(outcome.transactionsNew()).isEqualTo(3);
        assertThat(outcome.transactionsUpdated()).isEqualTo(1);
        assertThat(outcome.pendingReplaced()).isEqualTo(2);
        assertThat(outcome.rowsSkipped()).isEqualTo(1);
        assertThat(outcome.toReview()).isEqualTo(2);
        assertThat(outcome.errorCode()).isNull();
        // R9.6: a sync only reads; it never asks the provider for a refresh.
        assertThat(connector.refreshCalls).isZero();
    }

    @Test
    void holderInfoFailureIsBestEffort() {
        connector.holderError = new ConnectorError(ConnectorErrorClass.TRANSIENT, "boom");
        accounts();

        orchestrator.run(RUN_ID);

        verify(accountService).upsertAccounts(eq(CONNECTION_ID), isNull(), anyList());
        assertThat(finishedOutcome().status()).isEqualTo(SyncRunStatus.SUCCEEDED);
    }

    @Test
    void oneFailingAccountMakesTheRunPartialAndMapsTheConnectionStatus() {
        bankAccount(1L, null);
        BankAccountEntity second = bankAccount(2L, null);
        accounts(upsert(1L, "acc-1", true), upsert(2L, "acc-2", true));
        ConnectorError transientError = new ConnectorError(ConnectorErrorClass.TRANSIENT, "STUB_TRANSIENT", null, "down");
        connector.accountErrors.put("acc-1", transientError);
        when(ingestService.ingestAll(eq(second), anyList(), eq(RUN_ID))).thenReturn(ingested(1, 21L));

        orchestrator.run(RUN_ID);

        verify(recorder).recordAccount(eq(RUN_ID), eq(1L), isNull(), eq(SyncRunStatus.FAILED), eq("STUB_TRANSIENT"));
        verify(recorder).recordAccount(eq(RUN_ID), eq(2L), any(), eq(SyncRunStatus.SUCCEEDED), isNull());
        verify(connectionService).recordConnectorFailure(CONNECTION_ID, transientError);
        RunOutcome outcome = finishedOutcome();
        assertThat(outcome.status()).isEqualTo(SyncRunStatus.PARTIAL);
        assertThat(outcome.errorClass()).isEqualTo("TRANSIENT");
        assertThat(outcome.transactionsNew()).isEqualTo(1);
    }

    @Test
    void allAccountsFailingFailsTheRun() {
        bankAccount(1L, null);
        accounts(upsert(1L, "acc-1", true));
        connector.accountErrors.put("acc-1", new ConnectorError(ConnectorErrorClass.PROVIDER, "ProviderUnavailable", null, "x"));

        orchestrator.run(RUN_ID);

        assertThat(finishedOutcome().status()).isEqualTo(SyncRunStatus.FAILED);
    }

    @Test
    void limitErrorKeepsTheRunPartialWithTheLimitCode() {
        bankAccount(1L, null);
        accounts(upsert(1L, "acc-1", true));
        ConnectorError limit = new ConnectorError(ConnectorErrorClass.LIMIT, "RateLimitExceeded", null, "slow down");
        connector.accountErrors.put("acc-1", limit);

        orchestrator.run(RUN_ID);

        RunOutcome outcome = finishedOutcome();
        assertThat(outcome.status()).isEqualTo(SyncRunStatus.PARTIAL);
        assertThat(outcome.errorCode()).isEqualTo("RateLimitExceeded");
        verify(connectionService).recordConnectorFailure(CONNECTION_ID, limit);
    }

    @Test
    void connectionStateFailureFailsTheRunAndMapsTheStatus() {
        ConnectorError reauth = new ConnectorError(ConnectorErrorClass.REAUTH, "AispConsentExpired", null, "expired");
        connector.stateError = reauth;

        orchestrator.run(RUN_ID);

        verify(connectionService).recordConnectorFailure(CONNECTION_ID, reauth);
        verify(accountService, never()).upsertAccounts(anyLong(), any(), anyList());
        RunOutcome outcome = finishedOutcome();
        assertThat(outcome.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("REAUTH");
        assertThat(outcome.errorCode()).isEqualTo("AispConsentExpired");
    }

    @Test
    void reauthRequiredStateFailsTheRunWithoutFetchingAccounts() {
        BankConnectionEntity reauth = new BankConnectionEntity();
        reauth.setId(CONNECTION_ID);
        reauth.setProvider(FeedProvider.SALT_EDGE);
        reauth.setStatus(BankConnectionStatus.REAUTH_REQUIRED);
        when(connectionService.applyConnectionState(eq(CONNECTION_ID), any())).thenReturn(reauth);

        orchestrator.run(RUN_ID);

        verify(accountService, never()).upsertAccounts(anyLong(), any(), anyList());
        assertThat(finishedOutcome().status()).isEqualTo(SyncRunStatus.FAILED);
    }

    @Test
    void disabledConnectionIsNotSynced() {
        connection.setStatus(BankConnectionStatus.DISABLED);

        orchestrator.run(RUN_ID);

        assertThat(connector.stateCalls).isZero();
        RunOutcome outcome = finishedOutcome();
        assertThat(outcome.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(BankSyncOrchestrator.CONNECTION_DISABLED);
    }

    @Test
    void runThatIsNoLongerQueuedIsSkipped() {
        when(recorder.markRunning(RUN_ID)).thenReturn(Optional.empty());

        orchestrator.run(RUN_ID);

        assertThat(connector.stateCalls).isZero();
        verify(recorder, never()).finish(anyLong(), any());
    }

    // ------------------------------------------------------------------ balance check (R7)

    @Test
    void balanceGapIsRecorded() {
        BankAccountEntity a = bankAccount(1L, null);
        OffsetDateTime prevAt = OffsetDateTime.of(2026, 1, 1, 6, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime newAt = OffsetDateTime.of(2026, 1, 15, 6, 0, 0, 0, ZoneOffset.UTC);
        accounts(new AccountUpsert(1L, "acc-1", false, true, new BigDecimal("100.00"), prevAt,
                new BigDecimal("150.00"), newAt));
        when(ingestService.ingestAll(eq(a), anyList(), eq(RUN_ID))).thenReturn(IngestResult.EMPTY);
        when(recorder.postedBetween(1L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 15))).thenReturn(List.of(
                new BalanceContinuityChecker.PostedAmount(LocalDate.of(2026, 1, 10), new BigDecimal("20.00"))));

        orchestrator.run(RUN_ID);

        ArgumentCaptor<BalanceContinuityChecker.Gap> gap = ArgumentCaptor.forClass(BalanceContinuityChecker.Gap.class);
        verify(recorder).recordGap(eq(1L), gap.capture(), eq(prevAt), eq(newAt));
        assertThat(gap.getValue().difference()).isEqualByComparingTo("30.00");
    }

    @Test
    void continuousBalanceRecordsNoGap() {
        BankAccountEntity a = bankAccount(1L, null);
        OffsetDateTime prevAt = OffsetDateTime.of(2026, 1, 1, 6, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime newAt = OffsetDateTime.of(2026, 1, 15, 6, 0, 0, 0, ZoneOffset.UTC);
        accounts(new AccountUpsert(1L, "acc-1", false, true, new BigDecimal("100.00"), prevAt,
                new BigDecimal("120.00"), newAt));
        when(ingestService.ingestAll(eq(a), anyList(), eq(RUN_ID))).thenReturn(IngestResult.EMPTY);
        when(recorder.postedBetween(anyLong(), any(), any())).thenReturn(List.of(
                new BalanceContinuityChecker.PostedAmount(LocalDate.of(2026, 1, 10), new BigDecimal("20.00"))));

        orchestrator.run(RUN_ID);

        verify(recorder, never()).recordGap(anyLong(), any(), any(), any());
    }

    // ------------------------------------------------------------------ cursor

    @Test
    void resumeCursorIsTheMaxPostedIdForSaltEdgeAndNullForOtherConnectors() {
        List<TransactionPage> pages = List.of(page(List.of(posted("9", "1.00"), posted("12", "1.00")), "page:1"),
                page(List.of(posted("10", "1.00")), null));

        assertThat(BankSyncOrchestrator.resumeCursor(FeedProvider.SALT_EDGE, "5", pages)).isEqualTo("12");
        assertThat(BankSyncOrchestrator.resumeCursor(FeedProvider.SALT_EDGE, "20", pages)).isEqualTo("20");
        assertThat(BankSyncOrchestrator.resumeCursor(FeedProvider.SALT_EDGE, "5", List.of(page(List.of(), null))))
                .isEqualTo("5");
        assertThat(BankSyncOrchestrator.resumeCursor(FeedProvider.STUB, "5", pages)).isNull();
    }

    @Test
    void nonAdvancingCursorIsRejected() {
        connector.pages.put("acc-1", List.of(page(List.of(), "page:0")));

        assertThatThrownBy(() -> BankSyncOrchestrator.fetchAllPages(connector,
                BankConnectionService.toRef(connection), "acc-1", "page:0"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ recovery (R5.6)

    @Test
    void startupRecoveryFailsStaleRunsAndResubmitsQueuedOnes() {
        when(recorder.failStale(any())).thenReturn(1);
        when(recorder.queuedRunIds()).thenReturn(List.of(RUN_ID));
        accounts();

        orchestrator.recoverOnStartup();

        verify(recorder).failStale(NOW.minus(orchestrator.staleAfter()));
        verify(recorder).markRunning(RUN_ID);
        assertThat(finishedOutcome().status()).isEqualTo(SyncRunStatus.SUCCEEDED);
    }

    @Test
    void unexpectedFailureStillFinishesTheRun() {
        doAnswer(inv -> {
            throw new IllegalStateException("db down");
        }).when(accountService).upsertAccounts(anyLong(), any(), anyList());

        orchestrator.run(RUN_ID);

        RunOutcome outcome = finishedOutcome();
        assertThat(outcome.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(BankSyncOrchestrator.INTERNAL);
    }
}
