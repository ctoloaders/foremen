package com.foremen.service.bank.connector.saltedge;

import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankSyncRunDao;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.BankSyncRunEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SyncTrigger;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.connection.BankConnectionService;
import com.foremen.service.bank.connection.BankSyncTrigger;
import com.foremen.service.bank.link.BankLedgerAudit;
import com.foremen.service.bank.sync.BankSyncExecutorConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FOR-07-04 (Requirements 10.3–10.9; design §Callbacks step 5): acts on a verified
 * {@link SaltEdgeCallbackReceived} asynchronously on {@code bankSyncExecutor}, after the
 * {@code 200} has been returned to Salt Edge.
 *
 * <p>Connection resolution: by {@code (SALT_EDGE, connection_id)}, else by
 * {@code custom_fields.foremenConnectionId} when that connection is a Salt Edge one whose stored
 * customer / provider connection id (if any) match the callback. Unknown connections are logged and
 * ignored without side effects (R10.8).
 *
 * <ul>
 *   <li>{@code success} → {@code completeIfPending}; when the connection was already authorized,
 *       a {@code CALLBACK} run is enqueued (coalesced by the orchestrator, R10.9). A completion
 *       itself already requests the {@code CONNECT} run, so no second run is requested then.</li>
 *   <li>{@code fail} → {@link SaltEdgeErrorMapper#fromClass} → {@code recordConnectorFailure}
 *       (status per R12.4, error stored, re-auth event on transition).</li>
 *   <li>{@code notify} → the stage is recorded on the latest run only (no data fetch).</li>
 *   <li>{@code destroy} → {@code DISABLED} locally (history kept; no provider call — the
 *       connection is already gone at Salt Edge).</li>
 * </ul>
 * Every branch is idempotent; failures are logged (ids only, never payloads) and swallowed.
 */
@Component
public class SaltEdgeCallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(SaltEdgeCallbackHandler.class);

    static final int STAGE_MAX = 64;

    private final BankConnectionDao connectionDao;
    private final BankSyncRunDao syncRunDao;
    private final BankConnectionService connectionService;
    private final ObjectProvider<BankSyncTrigger> syncTrigger;
    private final BankLedgerAudit audit;
    private final TransactionTemplate tx;

    public SaltEdgeCallbackHandler(BankConnectionDao connectionDao,
                                   BankSyncRunDao syncRunDao,
                                   BankConnectionService connectionService,
                                   ObjectProvider<BankSyncTrigger> syncTrigger,
                                   BankLedgerAudit audit,
                                   PlatformTransactionManager transactionManager) {
        this.connectionDao = connectionDao;
        this.syncRunDao = syncRunDao;
        this.connectionService = connectionService;
        this.syncTrigger = syncTrigger;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Async entry point (after the publisher's commit, or immediately outside a transaction). */
    @Async(BankSyncExecutorConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallback(SaltEdgeCallbackReceived callback) {
        handle(callback);
    }

    /** Synchronous handling; never throws. */
    public void handle(SaltEdgeCallbackReceived callback) {
        if (callback == null) {
            return;
        }
        try {
            Optional<BankConnectionEntity> resolved = resolve(callback);
            if (resolved.isEmpty()) {
                log.info("Salt Edge {} callback for an unknown connection; ignored", callback.type());
                return;
            }
            Long id = resolved.get().getId();
            switch (callback.type()) {
                case SaltEdgeCallbackReceived.SUCCESS -> onSuccess(resolved.get(), callback);
                case SaltEdgeCallbackReceived.FAIL -> onFail(id, callback);
                case SaltEdgeCallbackReceived.NOTIFY -> onNotify(id, callback);
                case SaltEdgeCallbackReceived.DESTROY -> onDestroy(id);
                default -> log.debug("Salt Edge callback type {} ignored", callback.type());
            }
        } catch (ForemenApiException e) {
            log.warn("Salt Edge {} callback not applied: {}", callback.type(), e.getMessageCode());
        } catch (RuntimeException e) {
            log.warn("Salt Edge {} callback handling failed: {}", callback.type(), e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ resolution (R10.4, R10.8)

    Optional<BankConnectionEntity> resolve(SaltEdgeCallbackReceived callback) {
        String externalId = callback.connectionId();
        if (externalId != null) {
            Optional<BankConnectionEntity> byExternal =
                    connectionDao.findByProviderAndExternalConnectionId(FeedProvider.SALT_EDGE, externalId);
            if (byExternal.isPresent()) {
                return byExternal;
            }
        }
        Long foremenId = parseId(callback.foremenConnectionId());
        if (foremenId == null) {
            return Optional.empty();
        }
        return connectionDao.findById(foremenId)
                .filter(c -> c.getProvider() == FeedProvider.SALT_EDGE)
                .filter(c -> matchesOrUnset(c.getExternalCustomerId(), callback.customerId()))
                .filter(c -> matchesOrUnset(c.getExternalConnectionId(), externalId));
    }

    private static boolean matchesOrUnset(String stored, String reported) {
        return isBlank(stored) || reported == null || Objects.equals(stored.trim(), reported);
    }

    // ------------------------------------------------------------------ branches

    private void onSuccess(BankConnectionEntity connection, SaltEdgeCallbackReceived callback) {
        Long id = connection.getId();
        if (connection.getStatus() == BankConnectionStatus.DISABLED) {
            log.info("Salt Edge success callback for disabled connection {}; ignored", id);
            return;
        }
        if (connection.getStatus() == BankConnectionStatus.PENDING_AUTH) {
            Map<String, String> params = new LinkedHashMap<>();
            if (callback.connectionId() != null) {
                params.put("connection_id", callback.connectionId());
            }
            BankConnectionService.CompletionOutcome outcome = connectionService.completeIfPending(id, params);
            if (outcome.completed()) {
                // completion already requested the CONNECT run (R1.4)
                log.info("Salt Edge success callback completed connection {}", id);
                return;
            }
            BankConnectionStatus status = outcome.connection().getStatus();
            if (status == BankConnectionStatus.PENDING_AUTH || status == BankConnectionStatus.DISABLED) {
                return;
            }
        }
        BankSyncTrigger trigger = syncTrigger.getIfAvailable();
        if (trigger == null) {
            log.warn("No bank sync orchestrator present; CALLBACK sync for connection {} not requested", id);
            return;
        }
        trigger.enqueue(id, SyncTrigger.CALLBACK, BankLedgerAudit.currentUser());
    }

    private void onFail(Long id, SaltEdgeCallbackReceived callback) {
        connectionService.recordConnectorFailure(id,
                SaltEdgeErrorMapper.fromClass(callback.errorClass(), callback.errorMessage()));
        log.info("Salt Edge fail callback recorded for connection {} ({})", id,
                SaltEdgeErrorMapper.classify(callback.errorClass()));
    }

    private void onNotify(Long id, SaltEdgeCallbackReceived callback) {
        String stage = callback.stage();
        if (stage == null) {
            return;
        }
        String truncated = stage.length() > STAGE_MAX ? stage.substring(0, STAGE_MAX) : stage;
        tx.executeWithoutResult(status -> {
            List<BankSyncRunEntity> latest = syncRunDao.findByConnectionIdOrderByIdDesc(id, PageRequest.of(0, 1));
            if (latest.isEmpty() || truncated.equals(latest.get(0).getStage())) {
                return;
            }
            BankSyncRunEntity run = latest.get(0);
            run.setStage(truncated);
            syncRunDao.save(run);
        });
    }

    private void onDestroy(Long id) {
        tx.executeWithoutResult(status -> connectionDao.findById(id).ifPresent(connection -> {
            if (connection.getStatus() == BankConnectionStatus.DISABLED) {
                return;
            }
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("status", connection.getStatus().name());
            connection.setStatus(BankConnectionStatus.DISABLED);
            connectionDao.save(connection);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", BankConnectionStatus.DISABLED.name());
            after.put("source", "saltedge.destroy");
            audit.write(BankConnectionEntity.class, id, "DISABLE", before, after);
            log.info("Salt Edge destroy callback disabled connection {}", id);
        }));
    }

    // ------------------------------------------------------------------ helpers

    private static Long parseId(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
