package com.foremen.service.bank.connection;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.dao.model.SyncTrigger;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.PublicBaseUrl;
import com.foremen.service.bank.connector.AuthorizationStart;
import com.foremen.service.bank.connector.BankConnectionRef;
import com.foremen.service.bank.connector.BankFeedConnector;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.connector.ConnectionState;
import com.foremen.service.bank.connector.ConnectionStatusHint;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.RefreshMode;
import com.foremen.service.bank.connector.RefreshResult;
import com.foremen.service.bank.connector.saltedge.SaltEdgeConnector;
import com.foremen.service.bank.link.BankLedgerAudit;
import com.foremen.service.bank.notify.ConsentExpiringEvent;
import com.foremen.service.bank.notify.ReauthRequiredEvent;
import com.foremen.service.bank.secret.BankConnectionSecretService;

/**
 * FOR-07-04 (Requirements 1.2–1.10, 9.3–9.5, 12.4, 24.4, 24.5) — lifecycle of a {@code Bank_Connection}.
 *
 * <h2>User actions (all audited)</h2>
 * <ul>
 *   <li>{@link #create} — persists a {@code PENDING_AUTH} connection (with the Salt Edge
 *       {@code provider_mode} it is created in), asks the connector for the authorization URL
 *       (return URL = {@link PublicBaseUrl#returnUrl(long)}) and stores the provider customer id.</li>
 *   <li>{@link #continueAuthorization} — a fresh authorization URL for a {@code PENDING_AUTH}
 *       connection (R1.10).</li>
 *   <li>{@link #complete} — completes authorization; the return parameters are only hints, the result
 *       is validated against the provider with {@code fetchConnectionState} (R24.5). A transition into
 *       {@code ACTIVE} / {@code CONSENT_EXPIRING} requests a {@code CONNECT} sync (R1.4).</li>
 *   <li>{@link #reconnect} — reconnect URL for the same provider connection (R1.5).</li>
 *   <li>{@link #refresh} — provider refresh; {@code 409 error.bank.refresh.notYetPossible} with the
 *       earliest allowed time when limited (R1.6).</li>
 *   <li>{@link #requestSync} — "Sync now" ({@code MANUAL}); {@code 409} when disabled / not authorized.</li>
 *   <li>{@link #rename}, {@link #disable} (revokes at the provider, R1.7) and {@link #delete}
 *       ({@code DISABLED} or {@code PENDING_AUTH} only; {@code 409 error.bank.connection.hasLinks}
 *       when any of its transactions has a link, R1.8).</li>
 * </ul>
 *
 * <h2>System operations (used by the sync orchestrator / consent sweep / callbacks)</h2>
 * {@link #completeIfPending}, {@link #applyConnectionState}, {@link #recordConnectorFailure},
 * {@link #recomputeConsentStatus}, {@link #recomputeConsentStatuses} and {@link #purgeStalePendingAuth}.
 * Status changes go through one transition point that publishes {@link ReauthRequiredEvent} only on the
 * transition into {@code REAUTH_REQUIRED} and {@link ConsentExpiringEvent} whenever the status is
 * {@code CONSENT_EXPIRING} (the emitter de-duplicates per consent).
 *
 * <p>Sync runs are requested through the {@link BankSyncTrigger} seam (task 6.4), looked up lazily.
 */
@Service
public class BankConnectionService {

    private static final Logger log = LoggerFactory.getLogger(BankConnectionService.class);

    static final String NOT_FOUND = "error.entity.not.found";
    /** 400 when {@code provider} is missing. */
    public static final String PROVIDER_REQUIRED = "error.bank.provider.required";
    /** 409 when {@code foremen.public-base-url} is not usable (return URL cannot be built). */
    public static final String PUBLIC_BASE_URL_NOT_CONFIGURED = "error.bank.publicBaseUrl.notConfigured";
    /** 409 when an action needs a {@code PENDING_AUTH} connection. */
    public static final String NOT_PENDING = "error.bank.connection.notPending";
    /** 409 when the connection is {@code DISABLED}. */
    public static final String DISABLED = "error.bank.connection.disabled";
    /** 409 when the connection has not been authorized yet. */
    public static final String NOT_AUTHORIZED = "error.bank.connection.notAuthorized";
    /** 409 when deleting a connection that is neither {@code DISABLED} nor {@code PENDING_AUTH}. */
    public static final String NOT_DISABLED = "error.bank.connection.notDisabled";
    /** 409 when deleting a connection whose transactions have links (R1.8). */
    public static final String HAS_LINKS = "error.bank.connection.hasLinks";
    /** 409 when the provider connection is already bound to another Foremen connection. */
    public static final String ALREADY_LINKED = "error.bank.connection.alreadyLinked";
    /** 409 when completion reports a provider connection different from the stored one. */
    public static final String MISMATCH = "error.bank.connection.mismatch";
    /** 409 when a refresh is not allowed yet (R1.6); param 0 = earliest allowed time (ISO-8601). */
    public static final String REFRESH_NOT_YET = "error.bank.refresh.notYetPossible";
    /** 400 when a rename supplies a blank / too long display name. */
    public static final String DISPLAY_NAME_INVALID = "error.bank.connection.displayNameInvalid";
    /** 409 when "Sync now" is requested while no sync orchestrator is present. */
    public static final String SYNC_UNAVAILABLE = "error.bank.sync.unavailable";

    static final int DISPLAY_NAME_MAX = 255;
    static final int ERROR_CODE_MAX = 64;
    static final int DEFAULT_WARN_DAYS = 14;

    private final BankConnectionDao connectionDao;
    private final BankAccountDao accountDao;
    private final BankTransactionLinkDao linkDao;
    private final BankFeedConnectorRegistry registry;
    private final BankConnectionSecretService secretService;
    private final PublicBaseUrl publicBaseUrl;
    private final BankLedgerAudit audit;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<BankSyncTrigger> syncTrigger;
    private final Duration warnBefore;
    private Clock clock = Clock.systemUTC();

    public BankConnectionService(BankConnectionDao connectionDao,
                                 BankAccountDao accountDao,
                                 BankTransactionLinkDao linkDao,
                                 BankFeedConnectorRegistry registry,
                                 BankConnectionSecretService secretService,
                                 PublicBaseUrl publicBaseUrl,
                                 BankLedgerAudit audit,
                                 ApplicationEventPublisher events,
                                 ObjectProvider<BankSyncTrigger> syncTrigger,
                                 @Value("${foremen.bank-feed.consent.warn-days:" + DEFAULT_WARN_DAYS + "}") int warnDays) {
        this.connectionDao = connectionDao;
        this.accountDao = accountDao;
        this.linkDao = linkDao;
        this.registry = registry;
        this.secretService = secretService;
        this.publicBaseUrl = publicBaseUrl;
        this.audit = audit;
        this.events = events;
        this.syncTrigger = syncTrigger;
        this.warnBefore = Duration.ofDays(warnDays > 0 ? warnDays : DEFAULT_WARN_DAYS);
    }

    /** Test hook. */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The consent warning window ({@code foremen.bank-feed.consent.warn-days}). */
    public Duration warnBefore() {
        return warnBefore;
    }

    // ------------------------------------------------------------------ results

    /** A connection plus the URL the frontend redirects the user to. */
    public record AuthorizationOutcome(BankConnectionEntity connection, URI authorizationUrl) {
    }

    /**
     * Result of a completion.
     *
     * @param connection the updated connection
     * @param completed  {@code true} when this call moved the connection into a healthy status
     * @param syncRunId  the {@code CONNECT} run requested for it, when any
     */
    public record CompletionOutcome(BankConnectionEntity connection, boolean completed, Optional<Long> syncRunId) {
    }

    /**
     * Result of "Refresh now".
     *
     * @param connection            the updated connection
     * @param authorizationUrl      widget URL when the provider needs the user (SCA); nullable
     * @param nextRefreshPossibleAt the earliest next refresh; nullable
     * @param syncRunId             a {@code MANUAL} run requested when no user interaction is needed
     */
    public record RefreshOutcome(BankConnectionEntity connection, URI authorizationUrl,
                                 OffsetDateTime nextRefreshPossibleAt, Optional<Long> syncRunId) {
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<BankConnectionEntity> list() {
        List<BankConnectionEntity> all = new ArrayList<>();
        connectionDao.findAll(Sort.by(Sort.Direction.ASC, "id")).forEach(all::add);
        return all;
    }

    @Transactional(readOnly = true)
    public BankConnectionEntity get(Long id) {
        return load(id);
    }

    // ------------------------------------------------------------------ create / authorize (R1.3, R1.10)

    /**
     * "Add bank": a {@code PENDING_AUTH} connection + the authorization URL. A connector failure rolls
     * the new connection back.
     */
    @Transactional
    public AuthorizationOutcome create(FeedProvider provider, String providerBankCode, String displayName) {
        if (provider == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, PROVIDER_REQUIRED);
        }
        BankFeedConnector connector = requireConnector(provider);
        secretService.assertUsable(provider);
        requirePublicBaseUrl();

        BankConnectionEntity draft = new BankConnectionEntity();
        draft.setProvider(provider);
        draft.setProviderMode(modeOf(connector));
        draft.setProviderCode(trimToNull(providerBankCode));
        draft.setDisplayName(normalizeDisplayName(displayName, true));
        draft.setStatus(BankConnectionStatus.PENDING_AUTH);
        // Persisted first: connectors (e.g. STUB, Salt Edge custom_fields) need the connection id.
        BankConnectionEntity connection = connectionDao.save(draft);

        AuthorizationStart start = callConnector(() -> connector.startAuthorization(
                toRef(connection), connection.getProviderCode(), returnUrl(connection)));
        storeCustomerId(connection, start);
        BankConnectionEntity saved = connectionDao.save(connection);
        audit.write(BankConnectionEntity.class, saved.getId(), "CREATE", null, snapshot(saved));
        return new AuthorizationOutcome(saved, start.authorizationUrl());
    }

    /** "Continue authorization": a fresh authorization URL for a {@code PENDING_AUTH} connection. */
    @Transactional
    public AuthorizationOutcome continueAuthorization(Long id) {
        BankConnectionEntity connection = load(id);
        if (connection.getStatus() != BankConnectionStatus.PENDING_AUTH) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_PENDING);
        }
        BankFeedConnector connector = requireConnector(connection.getProvider());
        requirePublicBaseUrl();
        AuthorizationStart start = callConnector(() -> connector.startAuthorization(
                toRef(connection), connection.getProviderCode(), returnUrl(connection)));
        storeCustomerId(connection, start);
        BankConnectionEntity saved = connectionDao.save(connection);
        audit.write(BankConnectionEntity.class, saved.getId(), "AUTHORIZE", null, snapshot(saved));
        return new AuthorizationOutcome(saved, start.authorizationUrl());
    }

    // ------------------------------------------------------------------ complete (R1.4, R9.3, R24.5)

    /**
     * Completes authorization from the return route. The parameters are passed to the connector as
     * hints only; the stored state comes from {@code fetchConnectionState}. Idempotent: completing an
     * already healthy connection only refreshes its state and requests no new sync.
     */
    @Transactional
    public CompletionOutcome complete(Long id, Map<String, String> returnParams) {
        BankConnectionEntity connection = load(id);
        Map<String, Object> before = snapshot(connection);
        CompletionOutcome outcome = doComplete(connection, returnParams);
        audit.write(BankConnectionEntity.class, connection.getId(), "COMPLETE", before, snapshot(outcome.connection()));
        return outcome;
    }

    /**
     * Callback path (R10.4): completes only while the connection is still {@code PENDING_AUTH};
     * otherwise a no-op returning {@code completed = false}.
     */
    @Transactional
    public CompletionOutcome completeIfPending(Long id, Map<String, String> params) {
        BankConnectionEntity connection = load(id);
        if (connection.getStatus() != BankConnectionStatus.PENDING_AUTH) {
            return new CompletionOutcome(connection, false, Optional.empty());
        }
        return doComplete(connection, params);
    }

    private CompletionOutcome doComplete(BankConnectionEntity connection, Map<String, String> returnParams) {
        if (connection.getStatus() == BankConnectionStatus.DISABLED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, DISABLED);
        }
        BankFeedConnector connector = requireConnector(connection.getProvider());
        Map<String, String> params = returnParams == null ? Map.of() : Map.copyOf(returnParams);
        ConnectionState reported = callConnector(() -> connector.completeAuthorization(toRef(connection), params));
        String externalId = trimToNull(reported == null ? null : reported.externalConnectionId());
        if (externalId == null) {
            externalId = trimToNull(connection.getExternalConnectionId());
        }
        if (externalId == null || reported == null || reported.status() == ConnectionStatusHint.PENDING_AUTH) {
            // Authorization not finished at the provider yet (e.g. callback not arrived): keep PENDING_AUTH.
            return new CompletionOutcome(connection, false, Optional.empty());
        }
        String stored = trimToNull(connection.getExternalConnectionId());
        if (stored != null && !stored.equals(externalId)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, MISMATCH);
        }
        assertNotBoundElsewhere(connection, externalId);

        // R24.5: validate against the provider — the persisted state comes from fetchConnectionState.
        BankConnectionRef validatedRef = withExternalConnectionId(toRef(connection), externalId);
        ConnectionState validated = callConnector(() -> connector.fetchConnectionState(validatedRef));
        if (validated == null || validated.status() == ConnectionStatusHint.PENDING_AUTH) {
            return new CompletionOutcome(connection, false, Optional.empty());
        }
        if (trimToNull(validated.externalConnectionId()) == null) {
            validated = new ConnectionState(validated.status(), externalId, validated.externalConsentId(),
                    validated.consentExpiresAt(), validated.nextRefreshPossibleAt(), validated.providerCode(),
                    validated.bankName(), validated.bankLogoUrl());
        } else if (!externalId.equals(validated.externalConnectionId().trim())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, MISMATCH);
        }
        BankConnectionStatus previous = connection.getStatus();
        applyState(connection, validated);
        connection.setLastErrorCode(null);
        connection.setLastErrorMessage(null);
        BankConnectionEntity saved = connectionDao.save(connection);
        boolean completed = !ConnectionStatusCalculator.isHealthy(previous)
                && ConnectionStatusCalculator.isHealthy(saved.getStatus());
        Optional<Long> runId = completed
                ? enqueue(saved.getId(), SyncTrigger.CONNECT)
                : Optional.empty();
        return new CompletionOutcome(saved, completed, runId);
    }

    private void assertNotBoundElsewhere(BankConnectionEntity connection, String externalId) {
        connectionDao.findByProviderAndExternalConnectionId(connection.getProvider(), externalId)
                .filter(other -> !Objects.equals(other.getId(), connection.getId()))
                .ifPresent(other -> {
                    throw new ForemenApiException(HttpStatus.CONFLICT, ALREADY_LINKED);
                });
    }

    // ------------------------------------------------------------------ reconnect / refresh / sync (R1.5, R1.6)

    /** "Reconnect": a reconnect URL for the same provider connection (renews consent). */
    @Transactional
    public AuthorizationOutcome reconnect(Long id) {
        BankConnectionEntity connection = load(id);
        if (connection.getStatus() == BankConnectionStatus.DISABLED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, DISABLED);
        }
        if (connection.getStatus() == BankConnectionStatus.PENDING_AUTH) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_AUTHORIZED);
        }
        BankFeedConnector connector = requireConnector(connection.getProvider());
        requirePublicBaseUrl();
        AuthorizationStart start = callConnector(() -> connector.startReconnect(toRef(connection), returnUrl(connection)));
        storeCustomerId(connection, start);
        BankConnectionEntity saved = connectionDao.save(connection);
        audit.write(BankConnectionEntity.class, saved.getId(), "RECONNECT", null, snapshot(saved));
        return new AuthorizationOutcome(saved, start.authorizationUrl());
    }

    /**
     * "Refresh now". Rejects with {@code 409 error.bank.refresh.notYetPossible} (param = earliest time)
     * when the stored or provider-reported {@code nextRefreshPossibleAt} is in the future; the new time
     * is persisted even then. A refresh that needs no user interaction requests a {@code MANUAL} sync.
     */
    @Transactional(noRollbackFor = ForemenApiException.class)
    public RefreshOutcome refresh(Long id) {
        BankConnectionEntity connection = load(id);
        requireAuthorized(connection);
        Instant now = clock.instant();
        OffsetDateTime storedNext = connection.getNextRefreshPossibleAt();
        if (storedNext != null && storedNext.toInstant().isAfter(now)) {
            throw notYet(storedNext);
        }
        BankFeedConnector connector = requireConnector(connection.getProvider());
        RefreshResult result = callConnector(() -> connector.requestRefresh(toRef(connection), RefreshMode.INTERACTIVE));
        OffsetDateTime next = toOffset(result.nextRefreshPossibleAt());
        connection.setNextRefreshPossibleAt(next);
        BankConnectionEntity saved = connectionDao.save(connection);
        if (!result.started()) {
            throw notYet(next);
        }
        audit.write(BankConnectionEntity.class, saved.getId(), "REFRESH", null, snapshot(saved));
        Optional<Long> runId = result.widgetUrl() == null
                ? enqueue(saved.getId(), SyncTrigger.MANUAL)
                : Optional.empty();
        return new RefreshOutcome(saved, result.widgetUrl(), next, runId);
    }

    /**
     * "Sync now" ({@code MANUAL}).
     *
     * @return the queued (or coalesced) run id
     * @throws ForemenApiException 409 when disabled / not authorized / no orchestrator
     */
    @Transactional
    public Long requestSync(Long id) {
        BankConnectionEntity connection = load(id);
        requireAuthorized(connection);
        BankSyncTrigger trigger = syncTrigger.getIfAvailable();
        if (trigger == null) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SYNC_UNAVAILABLE);
        }
        Long runId = trigger.enqueue(connection.getId(), SyncTrigger.MANUAL, BankLedgerAudit.currentUser())
                .orElseThrow(() -> new ForemenApiException(HttpStatus.CONFLICT, SYNC_UNAVAILABLE));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("runId", runId);
        after.put("trigger", SyncTrigger.MANUAL.name());
        audit.write(BankConnectionEntity.class, connection.getId(), "SYNC_REQUEST", null, after);
        return runId;
    }

    // ------------------------------------------------------------------ rename / disable / delete (R1.7, R1.8)

    @Transactional
    public BankConnectionEntity rename(Long id, String displayName) {
        BankConnectionEntity connection = load(id);
        Map<String, Object> before = snapshot(connection);
        connection.setDisplayName(normalizeDisplayName(displayName, false));
        BankConnectionEntity saved = connectionDao.save(connection);
        audit.write(BankConnectionEntity.class, saved.getId(), "UPDATE", before, snapshot(saved));
        return saved;
    }

    /**
     * Revokes provider-side access (when the connection was ever authorized) and sets {@code DISABLED};
     * accounts and transactions are kept. Idempotent. A provider reporting the connection already gone
     * ({@code REAUTH}) counts as success.
     */
    @Transactional
    public BankConnectionEntity disable(Long id) {
        BankConnectionEntity connection = load(id);
        if (connection.getStatus() == BankConnectionStatus.DISABLED) {
            return connection;
        }
        Map<String, Object> before = snapshot(connection);
        if (trimToNull(connection.getExternalConnectionId()) != null) {
            BankFeedConnector connector = requireConnector(connection.getProvider());
            try {
                connector.disable(toRef(connection));
            } catch (ConnectorError e) {
                if (e.getErrorClass() != ConnectorErrorClass.REAUTH) {
                    throw BankConnectorErrors.toApiException(e);
                }
                log.info("Bank connection {} already gone at the provider ({}); disabling locally",
                        connection.getId(), e.getProviderErrorCode());
            }
        }
        connection.setStatus(BankConnectionStatus.DISABLED);
        BankConnectionEntity saved = connectionDao.save(connection);
        audit.write(BankConnectionEntity.class, saved.getId(), "DISABLE", before, snapshot(saved));
        return saved;
    }

    /**
     * Deletes a {@code DISABLED} or abandoned {@code PENDING_AUTH} connection with its accounts,
     * transactions and runs (DB cascades). Rejected with {@code 409 error.bank.connection.hasLinks}
     * while any of its transactions has a link, or any link points to one of its accounts as the
     * internal-transfer counter account.
     */
    @Transactional
    public void delete(Long id) {
        BankConnectionEntity connection = load(id);
        if (connection.getStatus() != BankConnectionStatus.DISABLED
                && connection.getStatus() != BankConnectionStatus.PENDING_AUTH) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_DISABLED);
        }
        if (hasLinks(connection.getId())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, HAS_LINKS);
        }
        Map<String, Object> before = snapshot(connection);
        connectionDao.delete(connection);
        audit.write(BankConnectionEntity.class, id, "DELETE", before, null);
    }

    /** {@code true} when a link sits on a transaction of the connection or targets one of its accounts. */
    boolean hasLinks(Long connectionId) {
        return linkDao.count(linksOfConnection(connectionId)) > 0;
    }

    static Specification<BankTransactionLinkEntity> linksOfConnection(Long connectionId) {
        return (root, query, cb) -> {
            Join<BankTransactionLinkEntity, BankTransactionEntity> txn = root.join("transaction", JoinType.INNER);
            Join<BankTransactionEntity, BankAccountEntity> account = txn.join("account", JoinType.INNER);
            Join<BankTransactionLinkEntity, BankAccountEntity> counter = root.join("counterAccount", JoinType.LEFT);
            return cb.or(
                    cb.equal(account.get("connection").get("id"), connectionId),
                    cb.equal(counter.get("connection").get("id"), connectionId));
        };
    }

    // ------------------------------------------------------------------ system operations (R1.9, R1.10, R12.4)

    /**
     * Applies a provider state read by the sync orchestrator (status with the consent window, ids,
     * consent expiry, next refresh, bank name / logo).
     */
    @Transactional
    public BankConnectionEntity applyConnectionState(Long id, ConnectionState state) {
        BankConnectionEntity connection = load(id);
        if (state == null) {
            return connection;
        }
        applyState(connection, state);
        return connectionDao.save(connection);
    }

    /**
     * Stores a connector failure and maps it onto the status (R12.4): {@code REAUTH} →
     * {@code REAUTH_REQUIRED}; {@code CONFIG} / {@code PROVIDER} / {@code TRANSIENT} → {@code ERROR};
     * {@code LIMIT} → unchanged. A {@code DISABLED} connection keeps its status.
     */
    @Transactional
    public BankConnectionEntity recordConnectorFailure(Long id, ConnectorError error) {
        BankConnectionEntity connection = load(id);
        connection.setLastErrorCode(truncate(error.getProviderErrorCode() != null
                ? error.getProviderErrorCode() : error.getErrorClass().name(), ERROR_CODE_MAX));
        connection.setLastErrorMessage(error.getMessage());
        if (connection.getStatus() != BankConnectionStatus.DISABLED) {
            BankConnectionStatus next = switch (error.getErrorClass()) {
                case REAUTH -> BankConnectionStatus.REAUTH_REQUIRED;
                case CONFIG, PROVIDER, TRANSIENT -> BankConnectionStatus.ERROR;
                case LIMIT -> connection.getStatus();
            };
            transition(connection, next);
        }
        return connectionDao.save(connection);
    }

    /** Recomputes the consent-driven status of one connection from its stored expiry (R1.9). */
    @Transactional
    public BankConnectionEntity recomputeConsentStatus(Long id) {
        BankConnectionEntity connection = load(id);
        recompute(connection);
        return connectionDao.save(connection);
    }

    /** Daily sweep (R1.9): recomputes every {@code ACTIVE} / {@code CONSENT_EXPIRING} / {@code ERROR} connection. */
    @Transactional
    public int recomputeConsentStatuses() {
        int changed = 0;
        for (BankConnectionEntity connection : connectionDao.findByStatusInOrderByIdAsc(List.of(
                BankConnectionStatus.ACTIVE, BankConnectionStatus.CONSENT_EXPIRING, BankConnectionStatus.ERROR))) {
            BankConnectionStatus before = connection.getStatus();
            recompute(connection);
            if (before != connection.getStatus()) {
                connectionDao.save(connection);
                changed++;
            }
        }
        return changed;
    }

    /**
     * Daily sweep (R1.10): deletes {@code PENDING_AUTH} connections created more than {@code maxAge}
     * ago that have no accounts.
     *
     * @return the number of removed connections
     */
    @Transactional
    public int purgeStalePendingAuth(Duration maxAge) {
        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant().minus(maxAge), ZoneOffset.UTC);
        int removed = 0;
        for (BankConnectionEntity connection : connectionDao.findByStatusInOrderByIdAsc(
                List.of(BankConnectionStatus.PENDING_AUTH))) {
            LocalDateTime created = connection.getCreatedDate();
            if (created == null || !created.isBefore(cutoff)
                    || !accountDao.findByConnectionIdOrderByIdAsc(connection.getId()).isEmpty()) {
                continue;
            }
            Map<String, Object> before = snapshot(connection);
            connectionDao.delete(connection);
            audit.write(BankConnectionEntity.class, connection.getId(), "PURGE_PENDING", before, null);
            removed++;
        }
        return removed;
    }

    // ------------------------------------------------------------------ helpers

    private void recompute(BankConnectionEntity connection) {
        Instant expires = connection.getConsentExpiresAt() == null ? null : connection.getConsentExpiresAt().toInstant();
        transition(connection, ConnectionStatusCalculator.consentStatus(
                connection.getStatus(), expires, clock.instant(), warnBefore));
    }

    private void applyState(BankConnectionEntity connection, ConnectionState state) {
        if (trimToNull(state.externalConnectionId()) != null) {
            connection.setExternalConnectionId(state.externalConnectionId().trim());
        }
        if (trimToNull(state.externalConsentId()) != null) {
            connection.setExternalConsentId(state.externalConsentId().trim());
        }
        if (state.consentExpiresAt() != null) {
            connection.setConsentExpiresAt(toOffset(state.consentExpiresAt()));
        }
        connection.setNextRefreshPossibleAt(toOffset(state.nextRefreshPossibleAt()));
        if (trimToNull(state.providerCode()) != null) {
            connection.setProviderCode(state.providerCode().trim());
        }
        if (trimToNull(state.bankName()) != null) {
            connection.setBankName(state.bankName().trim());
        }
        if (trimToNull(state.bankLogoUrl()) != null) {
            connection.setBankLogoUrl(state.bankLogoUrl().trim());
        }
        if (connection.getDisplayName() == null && connection.getBankName() != null) {
            connection.setDisplayName(truncate(connection.getBankName(), DISPLAY_NAME_MAX));
        }
        Instant expires = connection.getConsentExpiresAt() == null ? null : connection.getConsentExpiresAt().toInstant();
        transition(connection, ConnectionStatusCalculator.fromHint(
                connection.getStatus(), state.status(), expires, clock.instant(), warnBefore));
    }

    /** The single status-transition point: publishes the notification events (R22). */
    private void transition(BankConnectionEntity connection, BankConnectionStatus next) {
        BankConnectionStatus previous = connection.getStatus();
        if (next == null) {
            return;
        }
        connection.setStatus(next);
        if (next == BankConnectionStatus.REAUTH_REQUIRED && previous != BankConnectionStatus.REAUTH_REQUIRED) {
            events.publishEvent(new ReauthRequiredEvent(connection.getId()));
        }
        if (next == BankConnectionStatus.CONSENT_EXPIRING) {
            events.publishEvent(new ConsentExpiringEvent(connection.getId(), connection.getExternalConsentId()));
        }
    }

    private Optional<Long> enqueue(Long connectionId, SyncTrigger trigger) {
        BankSyncTrigger seam = syncTrigger.getIfAvailable();
        if (seam == null) {
            log.warn("No bank sync orchestrator present; {} sync for connection {} not requested", trigger, connectionId);
            return Optional.empty();
        }
        return seam.enqueue(connectionId, trigger, BankLedgerAudit.currentUser());
    }

    private BankConnectionEntity load(Long id) {
        if (id == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        return connectionDao.findById(id).orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private BankFeedConnector requireConnector(FeedProvider provider) {
        return registry.find(provider)
                .filter(BankFeedConnector::isAvailable)
                .orElseThrow(BankConnectorErrors::providerNotAvailable);
    }

    private static void requireAuthorized(BankConnectionEntity connection) {
        if (connection.getStatus() == BankConnectionStatus.DISABLED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, DISABLED);
        }
        if (connection.getStatus() == BankConnectionStatus.PENDING_AUTH) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_AUTHORIZED);
        }
    }

    private void requirePublicBaseUrl() {
        if (!publicBaseUrl.isConfigured()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, PUBLIC_BASE_URL_NOT_CONFIGURED);
        }
    }

    private URI returnUrl(BankConnectionEntity connection) {
        return URI.create(publicBaseUrl.returnUrl(connection.getId()));
    }

    private static ForemenApiException notYet(OffsetDateTime at) {
        return new ForemenApiException(HttpStatus.CONFLICT, REFRESH_NOT_YET, at == null ? "" : at.toString());
    }

    private static <T> T callConnector(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (ConnectorError e) {
            throw BankConnectorErrors.toApiException(e);
        }
    }

    private static void storeCustomerId(BankConnectionEntity connection, AuthorizationStart start) {
        if (start != null && trimToNull(start.externalCustomerId()) != null) {
            connection.setExternalCustomerId(start.externalCustomerId().trim());
        }
    }

    private static SaltEdgeMode modeOf(BankFeedConnector connector) {
        return connector instanceof SaltEdgeConnector saltEdge ? saltEdge.mode() : null;
    }

    /** The read-only connector view of a connection. */
    public static BankConnectionRef toRef(BankConnectionEntity c) {
        return new BankConnectionRef(c.getId(), c.getProvider(), c.getProviderMode(), c.getExternalCustomerId(),
                c.getExternalConnectionId(), c.getExternalConsentId(), c.getProviderCode());
    }

    private static BankConnectionRef withExternalConnectionId(BankConnectionRef ref, String externalConnectionId) {
        return new BankConnectionRef(ref.id(), ref.provider(), ref.providerMode(), ref.externalCustomerId(),
                externalConnectionId, ref.externalConsentId(), ref.providerCode());
    }

    private static String normalizeDisplayName(String raw, boolean optional) {
        String value = trimToNull(raw);
        if (value == null) {
            if (optional) {
                return null;
            }
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, DISPLAY_NAME_INVALID);
        }
        if (value.length() > DISPLAY_NAME_MAX) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, DISPLAY_NAME_INVALID);
        }
        return value;
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /** Audited fields (ids and status only — never secrets or provider payloads). */
    static Map<String, Object> snapshot(BankConnectionEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("provider", c.getProvider() == null ? null : c.getProvider().name());
        m.put("providerMode", c.getProviderMode() == null ? null : c.getProviderMode().name());
        m.put("providerCode", c.getProviderCode());
        m.put("displayName", c.getDisplayName());
        m.put("status", c.getStatus() == null ? null : c.getStatus().name());
        m.put("externalConnectionId", c.getExternalConnectionId());
        m.put("consentExpiresAt", c.getConsentExpiresAt() == null ? null : c.getConsentExpiresAt().toString());
        return m;
    }
}
