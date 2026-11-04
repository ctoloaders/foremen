package com.foremen.service.bank.sync;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import com.foremen.service.bank.connection.BankSyncTrigger;
import com.foremen.service.bank.connector.BankConnectionRef;
import com.foremen.service.bank.connector.BankFeedConnector;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.connector.ConnectionState;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.connector.NormalizedAccountRef;
import com.foremen.service.bank.connector.NormalizedTransaction;
import com.foremen.service.bank.connector.TransactionPage;
import com.foremen.service.bank.connector.saltedge.SaltEdgeMapper;
import com.foremen.service.bank.detect.DetectionService;
import com.foremen.service.bank.detect.DetectionService.DetectionSummary;
import com.foremen.service.bank.ingest.BalanceContinuityChecker;
import com.foremen.service.bank.ingest.BankTransactionIngestService;
import com.foremen.service.bank.ingest.IngestResult;
import com.foremen.service.bank.sync.SyncRunRecorder.ActiveRun;
import com.foremen.service.bank.sync.SyncRunRecorder.RunOutcome;
import com.foremen.service.bank.sync.SyncRunRecorder.RunStart;

/**
 * FOR-07-04 (Requirements 1.4, 1.9, 3.7, 5.1–5.6, 7.1, 7.2, 9.6, 12.4) — queues and executes
 * {@code Sync_Run}s. It is the {@link BankSyncTrigger} implementation used by
 * {@link BankConnectionService} (CONNECT / MANUAL), the callback handler (CALLBACK) and
 * {@link BankSyncScheduler} (SCHEDULE).
 *
 * <h2>Enqueue (R5.3, R5.4)</h2>
 * The {@code QUEUED} row is inserted in its own transaction; the partial UNIQUE index
 * ({@code one QUEUED/RUNNING run per connection}) rejects a second active run, which is then coalesced:
 * the caller gets the active run's id and, when that run is already {@code RUNNING}, one follow-up run
 * is requested for when it finishes (a {@code QUEUED} run will read fresh data anyway). The follow-up
 * marker is in memory — single-node assumption, as for the other sweeps. The asynchronous work is
 * submitted to {@code bankSyncExecutor} after the caller's transaction completes. A run left active
 * past {@code stale-after} is failed on the spot instead of absorbing new requests.
 *
 * <h2>Run steps (R5.2) — each in its own short transaction</h2>
 * <ol>
 *   <li>{@code RUNNING}; {@code fetchConnectionState} → {@link BankConnectionService#applyConnectionState}
 *       (consent window recomputed, R1.9). A connector error fails the run and maps the connection
 *       status (R12.4); {@code REAUTH_REQUIRED} / {@code DISABLED} fail the run.</li>
 *   <li>{@code fetchHolderInfo} (best effort) + {@code fetchAccounts} →
 *       {@link BankAccountService#upsertAccounts} (holder match, R3.7).</li>
 *   <li>Per included bank account — isolated (R5.5): fetch all pages from the stored cursor, ingest,
 *       store the resume cursor, then the balance check (R7). A failing account marks the run
 *       {@code PARTIAL}; its connector error is mapped onto the connection (R12.4).</li>
 *   <li>Detection on the POSTED {@code BANK_FEED} lines that became posted in this run.</li>
 *   <li>Finish with counters + {@code to_review}; {@code BankSyncFinished} (+ {@code SyncFailed}).</li>
 * </ol>
 * The scheduled pull only reads provider data — it never asks the provider for a (background) refresh
 * (R9.6).
 *
 * <h2>Resume cursor</h2>
 * The cursor contract is opaque per connector. For {@code SALT_EDGE} the stored resume cursor is the
 * max posted id seen ({@link SaltEdgeMapper#maxId}, previous cursor kept when nothing numeric arrived).
 * Other connectors ({@code STUB}: an in-fetch page offset) get {@code null}, i.e. each sync re-reads from
 * the start and the idempotent ingest de-duplicates.
 */
@Service
public class BankSyncOrchestrator implements BankSyncTrigger {

    private static final Logger log = LoggerFactory.getLogger(BankSyncOrchestrator.class);

    /** {@code requested_by} of background work. */
    public static final String SYSTEM = "SYSTEM";
    /** Run error when the executor queue is full. */
    public static final String REJECTED = "error.bank.sync.rejected";
    /** Run error for an unexpected (non-connector) failure. */
    public static final String INTERNAL = "error.bank.sync.internal";
    /** Run error when the connection was disabled before the run started. */
    public static final String CONNECTION_DISABLED = "error.bank.connection.disabled";
    /** Run error when the connection is not authorized (yet). */
    public static final String CONNECTION_NOT_AUTHORIZED = "error.bank.connection.notAuthorized";

    static final Duration DEFAULT_STALE_AFTER = Duration.ofMinutes(30);
    /** Hard stop for a connector that never reports {@code complete}. */
    static final int MAX_PAGES = 10_000;

    private final SyncRunRecorder recorder;
    private final BankConnectionDao connectionDao;
    private final BankAccountDao accountDao;
    private final BankConnectionService connectionService;
    private final BankAccountService accountService;
    private final BankTransactionIngestService ingestService;
    private final DetectionService detectionService;
    private final BankFeedConnectorRegistry registry;
    private final Executor executor;
    private final Duration staleAfter;
    private final Map<Long, FollowUp> followUps = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    /** A coalesced request to run once more after the active run. */
    record FollowUp(SyncTrigger trigger, String requestedBy) {
    }

    @Autowired
    @SuppressWarnings("java:S107")
    public BankSyncOrchestrator(SyncRunRecorder recorder,
                                BankConnectionDao connectionDao,
                                BankAccountDao accountDao,
                                BankConnectionService connectionService,
                                BankAccountService accountService,
                                BankTransactionIngestService ingestService,
                                DetectionService detectionService,
                                BankFeedConnectorRegistry registry,
                                @Qualifier(BankSyncExecutorConfig.EXECUTOR) Executor executor,
                                @Value("${foremen.bank-feed.sync.stale-after:30m}") String staleAfter) {
        this.recorder = recorder;
        this.connectionDao = connectionDao;
        this.accountDao = accountDao;
        this.connectionService = connectionService;
        this.accountService = accountService;
        this.ingestService = ingestService;
        this.detectionService = detectionService;
        this.registry = registry;
        this.executor = executor;
        this.staleAfter = parseDuration(staleAfter, DEFAULT_STALE_AFTER);
    }

    /** Test hook. */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Duration staleAfter() {
        return staleAfter;
    }

    // ============================================================================================
    // enqueue
    // ============================================================================================

    @Override
    public Optional<Long> enqueue(Long connectionId, SyncTrigger trigger, String requestedBy) {
        if (connectionId == null || trigger == null) {
            return Optional.empty();
        }
        String who = requestedBy == null || requestedBy.isBlank() ? SYSTEM : requestedBy;
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<ActiveRun> active = recorder.findActive(connectionId, staleCutoff());
            if (active.isPresent()) {
                ActiveRun run = active.get();
                if (run.stale()) {
                    recorder.failActive(run.runId(), SyncRunRecorder.STALE,
                            "Run left " + run.status() + " past the stale-after window");
                    continue;
                }
                if (run.status() == SyncRunStatus.RUNNING) {
                    followUps.put(connectionId, new FollowUp(trigger, who));
                }
                log.debug("Bank sync {} for connection {} coalesced into run {}", trigger, connectionId, run.runId());
                return Optional.of(run.runId());
            }
            try {
                Optional<Long> runId = recorder.insertQueued(connectionId, trigger, who);
                runId.ifPresent(this::submitAfterCompletion);
                return runId;
            } catch (DataIntegrityViolationException race) {
                // Another request inserted the active run in between: coalesce on the next pass.
                log.debug("Concurrent bank sync enqueue for connection {}; coalescing", connectionId);
            }
        }
        return recorder.findActive(connectionId, null).map(ActiveRun::runId);
    }

    /** Submits after the caller's transaction completes (immediately outside a transaction). */
    private void submitAfterCompletion(Long runId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    // The QUEUED row is already committed (REQUIRES_NEW): submit even after a rollback so
                    // it never blocks the connection; the run re-reads the connection and fails cleanly.
                    submit(runId);
                }
            });
        } else {
            submit(runId);
        }
    }

    void submit(Long runId) {
        try {
            executor.execute(() -> run(runId));
        } catch (RejectedExecutionException e) {
            log.warn("Bank sync executor rejected run {}; failing it", runId);
            recorder.failActive(runId, REJECTED, "Sync executor queue is full");
        }
    }

    // ============================================================================================
    // run
    // ============================================================================================

    /** Executes a queued run (on {@code bankSyncExecutor}). A run that is no longer QUEUED is skipped. */
    public void run(Long runId) {
        Optional<RunStart> start;
        try {
            start = recorder.markRunning(runId);
        } catch (RuntimeException e) {
            log.error("Bank sync run {} could not be started", runId, e);
            return;
        }
        if (start.isEmpty()) {
            return;
        }
        Long connectionId = start.get().connectionId();
        RunOutcome outcome;
        try {
            outcome = execute(runId, connectionId);
        } catch (RuntimeException e) {
            log.error("Bank sync run {} (connection {}) failed unexpectedly", runId, connectionId, e);
            outcome = new RunOutcome(SyncRunStatus.FAILED, 0, 0, 0, 0, 0, 0, null, INTERNAL, e.getMessage());
        }
        try {
            recorder.finish(runId, outcome);
        } catch (RuntimeException e) {
            log.error("Bank sync run {} could not be finished", runId, e);
            recorder.failActive(runId, INTERNAL, e.getMessage());
        }
        log.info("Bank sync run {} connection {} {}: accounts={} new={} updated={} pending={} skipped={} toReview={}",
                runId, connectionId, outcome.status(), outcome.accountsSeen(), outcome.transactionsNew(),
                outcome.transactionsUpdated(), outcome.pendingReplaced(), outcome.rowsSkipped(), outcome.toReview());
        FollowUp followUp = followUps.remove(connectionId);
        if (followUp != null) {
            enqueue(connectionId, followUp.trigger(), followUp.requestedBy());
        }
    }

    /** Mutable counters of one run. */
    private static final class Tally {
        int accountsSeen;
        IngestResult ingest = IngestResult.EMPTY;
        int included;
        int failedAccounts;
        boolean onlyLimitFailures = true;
        boolean detectionFailed;
        int toReview;
        ConnectorError firstError;
        String firstErrorCode;
        String firstErrorMessage;

        void accountFailed(ConnectorError e, String code, String message) {
            failedAccounts++;
            if (e == null || e.getErrorClass() != ConnectorErrorClass.LIMIT) {
                onlyLimitFailures = false;
            }
            if (firstErrorCode == null) {
                firstError = e;
                firstErrorCode = code;
                firstErrorMessage = message;
            }
        }

        RunOutcome outcome(SyncRunStatus status) {
            String errorClass = firstError == null ? null : firstError.getErrorClass().name();
            return new RunOutcome(status, accountsSeen, ingest.transactionsNew(), ingest.transactionsUpdated(),
                    ingest.pendingReplaced(), ingest.rowsSkipped(), toReview,
                    errorClass, firstErrorCode, firstErrorMessage);
        }
    }

    private RunOutcome execute(Long runId, Long connectionId) {
        Tally tally = new Tally();
        BankConnectionEntity connection = connectionDao.findById(connectionId).orElse(null);
        if (connection == null || connection.getStatus() == BankConnectionStatus.DISABLED) {
            return failed(tally, null, CONNECTION_DISABLED, "Connection disabled or removed");
        }
        if (connection.getStatus() == BankConnectionStatus.PENDING_AUTH) {
            return failed(tally, null, CONNECTION_NOT_AUTHORIZED, "Connection not authorized yet");
        }

        // 1. connection state (R5.5: a failure here fails the run; R12.4 status mapping)
        BankFeedConnector connector;
        BankConnectionRef ref;
        try {
            connector = registry.require(connection.getProvider());
            ConnectionState state = connector.fetchConnectionState(BankConnectionService.toRef(connection));
            connection = connectionService.applyConnectionState(connectionId, state);
            ref = BankConnectionService.toRef(connection);
        } catch (ConnectorError e) {
            return connectorFailure(tally, connectionId, e);
        }
        if (connection.getStatus() == BankConnectionStatus.REAUTH_REQUIRED) {
            ConnectorError reauth = new ConnectorError(ConnectorErrorClass.REAUTH, "Provider reports re-authorization required");
            return failed(tally, reauth, reauth.getMessageKey(), reauth.getMessage());
        }
        if (connection.getStatus() == BankConnectionStatus.DISABLED) {
            return failed(tally, null, CONNECTION_DISABLED, "Provider reports the connection disabled");
        }

        // 2. holder info (best effort) + accounts upsert with holder match (R3.7)
        HolderInfo holder = fetchHolder(connector, ref, connectionId);
        UpsertResult upsert;
        try {
            List<NormalizedAccount> accounts = connector.fetchAccounts(ref);
            upsert = accountService.upsertAccounts(connectionId, holder, accounts);
        } catch (ConnectorError e) {
            return connectorFailure(tally, connectionId, e);
        }
        tally.accountsSeen = upsert.accountsSeen();

        // 3. per included account, isolated (R5.5)
        List<Long> newPostedIds = new ArrayList<>();
        for (AccountUpsert account : upsert.accounts()) {
            if (!account.includedInLedger()) {
                continue;
            }
            tally.included++;
            syncAccount(runId, connectionId, connection.getProvider(), connector, ref, account, tally, newPostedIds);
        }

        // 4. detection on the lines that became POSTED in this run
        if (!newPostedIds.isEmpty()) {
            try {
                DetectionSummary summary = detectionService.detect(newPostedIds, runId);
                tally.toReview = summary.toReview();
            } catch (RuntimeException e) {
                log.error("Bank detection failed for run {}", runId, e);
                tally.detectionFailed = true;
                tally.toReview = newPostedIds.size();
            }
        }

        // 5. final status
        SyncRunStatus status;
        if (tally.failedAccounts == 0) {
            status = tally.detectionFailed ? SyncRunStatus.PARTIAL : SyncRunStatus.SUCCEEDED;
        } else if (tally.failedAccounts >= tally.included && !tally.onlyLimitFailures) {
            status = SyncRunStatus.FAILED;
        } else {
            status = SyncRunStatus.PARTIAL;
        }
        return tally.outcome(status);
    }

    private void syncAccount(Long runId, Long connectionId, FeedProvider provider, BankFeedConnector connector,
                             BankConnectionRef ref, AccountUpsert upsert, Tally tally, List<Long> newPostedIds) {
        Long accountId = upsert.accountId();
        try {
            BankAccountEntity account = accountDao.findById(accountId)
                    .orElseThrow(() -> new IllegalStateException("Bank account " + accountId + " vanished"));
            String previousCursor = account.getTransactionsCursor();
            List<TransactionPage> pages = fetchAllPages(connector, ref, upsert.externalId(), previousCursor);
            IngestResult result = ingestService.ingestAll(account, pages, runId);
            tally.ingest = tally.ingest.plus(result);
            newPostedIds.addAll(result.newPostedIds());
            String resume = resumeCursor(provider, previousCursor, pages);
            if (!Objects.equals(resume, previousCursor)) {
                recorder.storeCursor(accountId, resume);
            }
            recorder.recordAccount(runId, accountId, result, SyncRunStatus.SUCCEEDED, null);
            if (account.getType() == BankAccountType.BANK) {
                checkBalance(upsert);
            }
        } catch (ConnectorError e) {
            log.warn("Bank sync run {}: account {} failed ({} {})", runId, accountId, e.getErrorClass(),
                    e.getProviderErrorCode());
            String code = errorCode(e);
            tally.accountFailed(e, code, e.getMessage());
            safeRecordAccount(runId, accountId, code);
            try {
                connectionService.recordConnectorFailure(connectionId, e);
            } catch (RuntimeException inner) {
                log.warn("Could not record connector failure on connection {}", connectionId, inner);
            }
        } catch (RuntimeException e) {
            log.error("Bank sync run {}: account {} failed", runId, accountId, e);
            tally.accountFailed(null, INTERNAL, e.getMessage());
            safeRecordAccount(runId, accountId, INTERNAL);
        }
    }

    private void safeRecordAccount(Long runId, Long accountId, String code) {
        try {
            recorder.recordAccount(runId, accountId, null, SyncRunStatus.FAILED, code);
        } catch (RuntimeException e) {
            log.warn("Could not record failed account {} of run {}", accountId, runId, e);
        }
    }

    /** Reads one complete fetch: from the stored cursor, following {@code nextCursor} until {@code complete}. */
    static List<TransactionPage> fetchAllPages(BankFeedConnector connector, BankConnectionRef ref,
                                               String accountExternalId, String startCursor) {
        NormalizedAccountRef accountRef = new NormalizedAccountRef(accountExternalId);
        List<TransactionPage> pages = new ArrayList<>();
        String cursor = startCursor;
        for (int i = 0; i < MAX_PAGES; i++) {
            TransactionPage page = connector.fetchTransactions(ref, accountRef, cursor);
            if (page == null) {
                break;
            }
            pages.add(page);
            String next = page.nextCursor();
            if (page.complete() || next == null || next.isBlank()) {
                return pages;
            }
            if (next.equals(cursor)) {
                throw new IllegalStateException("Connector returned a non-advancing cursor for account "
                        + accountExternalId);
            }
            cursor = next;
        }
        if (pages.size() >= MAX_PAGES) {
            throw new IllegalStateException("Connector exceeded " + MAX_PAGES + " pages for account " + accountExternalId);
        }
        return pages;
    }

    /** See the class doc: Salt Edge → max posted id (never moving backwards); other providers → {@code null}. */
    static String resumeCursor(FeedProvider provider, String previous, List<TransactionPage> pages) {
        if (provider != FeedProvider.SALT_EDGE) {
            return null;
        }
        List<NormalizedTransaction> posted = new ArrayList<>();
        pages.forEach(p -> posted.addAll(p.posted()));
        String max = SaltEdgeMapper.maxId(posted);
        if (max == null) {
            return previous;
        }
        if (previous != null) {
            try {
                if (new BigInteger(previous).compareTo(new BigInteger(max)) > 0) {
                    return previous;
                }
            } catch (NumberFormatException ignored) {
                // A non-numeric stored cursor is replaced by the numeric max id.
            }
        }
        return max;
    }

    /** R7: compare the new provider balance with the previous one + POSTED amounts in between. */
    private void checkBalance(AccountUpsert upsert) {
        if (upsert.previousBalance() == null || upsert.previousBalanceAt() == null
                || upsert.balance() == null || upsert.balanceAt() == null) {
            return; // R7.4: skipped silently
        }
        List<BalanceContinuityChecker.PostedAmount> posted = recorder.postedBetween(upsert.accountId(),
                upsert.previousBalanceAt().toLocalDate(), upsert.balanceAt().toLocalDate());
        BalanceContinuityChecker.check(false, upsert.previousBalance(), upsert.previousBalanceAt(),
                        upsert.balance(), upsert.balanceAt(), posted)
                .ifPresent(gap -> {
                    log.warn("Balance gap on account {}: expected {} actual {} diff {}", upsert.accountId(),
                            gap.expected(), gap.actual(), gap.difference());
                    recorder.recordGap(upsert.accountId(), gap, upsert.previousBalanceAt(), upsert.balanceAt());
                });
    }

    private HolderInfo fetchHolder(BankFeedConnector connector, BankConnectionRef ref, Long connectionId) {
        try {
            return connector.fetchHolderInfo(ref).orElse(null);
        } catch (ConnectorError e) {
            log.warn("Holder info of connection {} not available ({} {}); keeping the stored one",
                    connectionId, e.getErrorClass(), e.getProviderErrorCode());
            return null;
        }
    }

    private RunOutcome connectorFailure(Tally tally, Long connectionId, ConnectorError e) {
        try {
            connectionService.recordConnectorFailure(connectionId, e);
        } catch (RuntimeException inner) {
            log.warn("Could not record connector failure on connection {}", connectionId, inner);
        }
        SyncRunStatus status = e.getErrorClass() == ConnectorErrorClass.LIMIT ? SyncRunStatus.PARTIAL : SyncRunStatus.FAILED;
        tally.firstError = e;
        tally.firstErrorCode = errorCode(e);
        tally.firstErrorMessage = e.getMessage();
        return tally.outcome(status);
    }

    private static RunOutcome failed(Tally tally, ConnectorError error, String code, String message) {
        tally.firstError = error;
        tally.firstErrorCode = code;
        tally.firstErrorMessage = message;
        return tally.outcome(SyncRunStatus.FAILED);
    }

    /** The raw provider class when known (R12.3), else the localized message key. */
    static String errorCode(ConnectorError e) {
        return e.getProviderErrorCode() != null && !e.getProviderErrorCode().isBlank()
                ? e.getProviderErrorCode() : e.getMessageKey();
    }

    // ============================================================================================
    // recovery (R5.6)
    // ============================================================================================

    /** Marks runs left active past {@code stale-after} {@code FAILED}. */
    public int recoverStale() {
        int recovered = recorder.failStale(staleCutoff());
        if (recovered > 0) {
            log.warn("Recovered {} stale bank sync run(s)", recovered);
        }
        return recovered;
    }

    /**
     * Start-up recovery: stale runs are failed, and the remaining {@code QUEUED} runs (lost with the
     * previous process' in-memory executor) are submitted again.
     */
    public void recoverOnStartup() {
        recoverStale();
        for (Long runId : recorder.queuedRunIds()) {
            submit(runId);
        }
    }

    private Instant staleCutoff() {
        return clock.instant().minus(staleAfter);
    }

    static Duration parseDuration(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            Duration d = DurationStyle.detectAndParse(raw.trim());
            return d.isNegative() || d.isZero() ? fallback : d;
        } catch (IllegalArgumentException e) {
            log.warn("Invalid duration '{}'; using {}", raw, fallback);
            return fallback;
        }
    }
}
