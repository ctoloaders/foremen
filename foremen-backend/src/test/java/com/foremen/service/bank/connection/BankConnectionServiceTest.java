package com.foremen.service.bank.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.FeedProvider;
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
import com.foremen.service.bank.link.BankLedgerAudit;
import com.foremen.service.bank.notify.ConsentExpiringEvent;
import com.foremen.service.bank.notify.ReauthRequiredEvent;
import com.foremen.service.bank.secret.BankConnectionSecretService;

/**
 * Unit tests for {@link BankConnectionService} (FOR-07-04).
 *
 * <p><b>Validates: Requirements 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 12.4, 24.4, 24.5</b>
 */
@ExtendWith(MockitoExtension.class)
class BankConnectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final String EXT_ID = "se-conn-1";

    @Mock private BankConnectionDao connectionDao;
    @Mock private BankAccountDao accountDao;
    @Mock private BankTransactionLinkDao linkDao;
    @Mock private BankConnectionSecretService secretService;
    @Mock private BankLedgerAudit audit;
    @Mock private ApplicationEventPublisher events;
    @Mock private ObjectProvider<BankSyncTrigger> syncTriggerProvider;
    @Mock private BankSyncTrigger syncTrigger;
    @Mock private BankFeedConnector connector;

    private final Map<Long, BankConnectionEntity> rows = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(100);
    private BankConnectionService service;

    @BeforeEach
    void setUp() {
        lenient().when(connector.provider()).thenReturn(FeedProvider.STUB);
        lenient().when(connector.isAvailable()).thenReturn(true);
        lenient().when(connectionDao.save(any())).thenAnswer(inv -> {
            BankConnectionEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ids.incrementAndGet());
            }
            rows.put(e.getId(), e);
            return e;
        });
        lenient().when(connectionDao.findById(any())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<Long>getArgument(0))));
        lenient().when(connectionDao.findByProviderAndExternalConnectionId(any(), any())).thenAnswer(inv ->
                rows.values().stream().filter(r -> inv.getArgument(1).equals(r.getExternalConnectionId())).findFirst());
        lenient().when(syncTriggerProvider.getIfAvailable()).thenReturn(syncTrigger);
        lenient().when(syncTrigger.enqueue(any(), any(), any())).thenReturn(Optional.of(555L));
        service = new BankConnectionService(connectionDao, accountDao, linkDao,
                new BankFeedConnectorRegistry(List.of(connector)), secretService,
                new PublicBaseUrl("http://localhost:3000/"), audit, events, syncTriggerProvider, 14);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private BankConnectionEntity existing(BankConnectionStatus status, String externalId) {
        BankConnectionEntity e = new BankConnectionEntity();
        e.setProvider(FeedProvider.STUB);
        e.setStatus(status);
        e.setExternalConnectionId(externalId);
        e.setId(ids.incrementAndGet());
        rows.put(e.getId(), e);
        return e;
    }

    private static ConnectionState state(ConnectionStatusHint hint, String externalId, Instant consentExpiresAt) {
        return new ConnectionState(hint, externalId, "consent-1", consentExpiresAt, null, "stub_business_pl",
                "Stub Bank Business", null);
    }

    // ------------------------------------------------------------------ create / authorize

    @Test
    void createPersistsPendingConnectionAndReturnsAuthorizationUrl() {
        URI url = URI.create("http://localhost:3000/bank/connections/return?connectionId=101&stub=1");
        when(connector.startAuthorization(any(), eq("stub_business_pl"), any()))
                .thenReturn(new AuthorizationStart(url, null, "cust-9"));

        BankConnectionService.AuthorizationOutcome out =
                service.create(FeedProvider.STUB, " stub_business_pl ", "  Firmowe  ");

        assertThat(out.authorizationUrl()).isEqualTo(url);
        BankConnectionEntity c = out.connection();
        assertThat(c.getId()).isNotNull();
        assertThat(c.getStatus()).isEqualTo(BankConnectionStatus.PENDING_AUTH);
        assertThat(c.getProviderMode()).isNull();
        assertThat(c.getProviderCode()).isEqualTo("stub_business_pl");
        assertThat(c.getDisplayName()).isEqualTo("Firmowe");
        assertThat(c.getExternalCustomerId()).isEqualTo("cust-9");
        ArgumentCaptor<BankConnectionRef> ref = ArgumentCaptor.forClass(BankConnectionRef.class);
        ArgumentCaptor<URI> returnUrl = ArgumentCaptor.forClass(URI.class);
        verify(connector).startAuthorization(ref.capture(), eq("stub_business_pl"), returnUrl.capture());
        assertThat(ref.getValue().id()).isEqualTo(c.getId());
        assertThat(returnUrl.getValue().toString())
                .isEqualTo("http://localhost:3000/bank/connections/return?connectionId=" + c.getId());
        verify(secretService).assertUsable(FeedProvider.STUB);
        verify(audit).write(eq(BankConnectionEntity.class), eq(c.getId()), eq("CREATE"), isNull(), anyMap());
    }

    @Test
    void createRejectsUnavailableProvider() {
        assertThatThrownBy(() -> service.create(FeedProvider.SALT_EDGE, null, null))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo(BankConnectorErrors.PROVIDER_NOT_AVAILABLE);
                });
        verify(connectionDao, never()).save(any());
    }

    @Test
    void createMapsConnectorErrorToLocalizedCode() {
        when(connector.startAuthorization(any(), any(), any())).thenThrow(new ConnectorError(
                ConnectorErrorClass.PROVIDER, "ProviderKeyNotFound", "error.bank.provider.keysRequired", "raw"));
        assertThatThrownBy(() -> service.create(FeedProvider.STUB, "pko", null))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo("error.bank.provider.keysRequired");
                });
    }

    @Test
    void continueAuthorizationRequiresPendingAuth() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        assertThatThrownBy(() -> service.continueAuthorization(c.getId()))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(BankConnectionService.NOT_PENDING));
    }

    // ------------------------------------------------------------------ complete

    @Test
    void completeValidatesWithProviderActivatesAndRequestsConnectSync() {
        BankConnectionEntity c = existing(BankConnectionStatus.PENDING_AUTH, null);
        Instant expires = NOW.plus(Duration.ofDays(90));
        when(connector.completeAuthorization(any(), anyMap()))
                .thenReturn(state(ConnectionStatusHint.ACTIVE, EXT_ID, expires));
        when(connector.fetchConnectionState(any())).thenReturn(state(ConnectionStatusHint.ACTIVE, EXT_ID, expires));

        BankConnectionService.CompletionOutcome out = service.complete(c.getId(), Map.of("connection_id", EXT_ID));

        assertThat(out.completed()).isTrue();
        assertThat(out.syncRunId()).contains(555L);
        assertThat(out.connection().getStatus()).isEqualTo(BankConnectionStatus.ACTIVE);
        assertThat(out.connection().getExternalConnectionId()).isEqualTo(EXT_ID);
        assertThat(out.connection().getExternalConsentId()).isEqualTo("consent-1");
        assertThat(out.connection().getConsentExpiresAt()).isEqualTo(expires.atOffset(ZoneOffset.UTC));
        assertThat(out.connection().getDisplayName()).isEqualTo("Stub Bank Business");
        ArgumentCaptor<BankConnectionRef> ref = ArgumentCaptor.forClass(BankConnectionRef.class);
        verify(connector).fetchConnectionState(ref.capture());
        assertThat(ref.getValue().externalConnectionId()).isEqualTo(EXT_ID);
        verify(syncTrigger).enqueue(c.getId(), SyncTrigger.CONNECT, "SYSTEM");
    }

    @Test
    void completeOfHealthyConnectionIsIdempotentWithoutNewSync() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        Instant expires = NOW.plus(Duration.ofDays(90));
        when(connector.completeAuthorization(any(), anyMap()))
                .thenReturn(state(ConnectionStatusHint.ACTIVE, EXT_ID, expires));
        when(connector.fetchConnectionState(any())).thenReturn(state(ConnectionStatusHint.ACTIVE, EXT_ID, expires));

        BankConnectionService.CompletionOutcome out = service.complete(c.getId(), Map.of());

        assertThat(out.completed()).isFalse();
        verify(syncTrigger, never()).enqueue(any(), any(), any());
    }

    @Test
    void completeKeepsPendingWhileProviderHasNoConnectionYet() {
        BankConnectionEntity c = existing(BankConnectionStatus.PENDING_AUTH, null);
        when(connector.completeAuthorization(any(), anyMap()))
                .thenReturn(state(ConnectionStatusHint.PENDING_AUTH, null, null));

        BankConnectionService.CompletionOutcome out = service.complete(c.getId(), Map.of());

        assertThat(out.completed()).isFalse();
        assertThat(out.connection().getStatus()).isEqualTo(BankConnectionStatus.PENDING_AUTH);
        verify(connector, never()).fetchConnectionState(any());
        verify(syncTrigger, never()).enqueue(any(), any(), any());
    }

    @Test
    void completeRejectsProviderConnectionBoundToAnotherConnection() {
        existing(BankConnectionStatus.ACTIVE, EXT_ID);
        BankConnectionEntity c = existing(BankConnectionStatus.PENDING_AUTH, null);
        when(connector.completeAuthorization(any(), anyMap()))
                .thenReturn(state(ConnectionStatusHint.ACTIVE, EXT_ID, null));

        assertThatThrownBy(() -> service.complete(c.getId(), Map.of("connection_id", EXT_ID)))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(BankConnectionService.ALREADY_LINKED));
        assertThat(c.getStatus()).isEqualTo(BankConnectionStatus.PENDING_AUTH);
    }

    @Test
    void completeRejectsDifferentProviderConnectionThanStored() {
        BankConnectionEntity c = existing(BankConnectionStatus.REAUTH_REQUIRED, EXT_ID);
        when(connector.completeAuthorization(any(), anyMap()))
                .thenReturn(state(ConnectionStatusHint.ACTIVE, "other", null));

        assertThatThrownBy(() -> service.complete(c.getId(), Map.of("connection_id", "other")))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(BankConnectionService.MISMATCH));
    }

    @Test
    void completeIfPendingIsNoOpForAuthorizedConnection() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        assertThat(service.completeIfPending(c.getId(), Map.of()).completed()).isFalse();
        verify(connector, never()).completeAuthorization(any(), anyMap());
    }

    // ------------------------------------------------------------------ reconnect / refresh / sync

    @Test
    void reconnectReturnsUrlForSameConnection() {
        BankConnectionEntity c = existing(BankConnectionStatus.REAUTH_REQUIRED, EXT_ID);
        URI url = URI.create("https://widget/reconnect");
        when(connector.startReconnect(any(), any())).thenReturn(new AuthorizationStart(url, null, null));

        BankConnectionService.AuthorizationOutcome out = service.reconnect(c.getId());

        assertThat(out.authorizationUrl()).isEqualTo(url);
        assertThat(out.connection().getId()).isEqualTo(c.getId());
        assertThat(out.connection().getExternalConnectionId()).isEqualTo(EXT_ID);
        verify(audit).write(eq(BankConnectionEntity.class), eq(c.getId()), eq("RECONNECT"), isNull(), anyMap());
    }

    @Test
    void reconnectOfDisabledConnectionIsRejected() {
        BankConnectionEntity c = existing(BankConnectionStatus.DISABLED, EXT_ID);
        assertThatThrownBy(() -> service.reconnect(c.getId()))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(BankConnectionService.DISABLED));
    }

    @Test
    void refreshRejectsWhenStoredNextRefreshIsInTheFuture() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        OffsetDateTime next = NOW.plus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC);
        c.setNextRefreshPossibleAt(next);

        assertThatThrownBy(() -> service.refresh(c.getId()))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo(BankConnectionService.REFRESH_NOT_YET);
                    assertThat(e.getMessageParams()).containsExactly(next.toString());
                });
        verify(connector, never()).requestRefresh(any(), any());
    }

    @Test
    void refreshRejectsAndPersistsProviderNextRefreshTime() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        Instant next = NOW.plus(Duration.ofMinutes(30));
        when(connector.requestRefresh(any(), eq(RefreshMode.INTERACTIVE))).thenReturn(RefreshResult.notYet(next));

        assertThatThrownBy(() -> service.refresh(c.getId()))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(BankConnectionService.REFRESH_NOT_YET));
        assertThat(rows.get(c.getId()).getNextRefreshPossibleAt()).isEqualTo(next.atOffset(ZoneOffset.UTC));
    }

    @Test
    void refreshWithoutWidgetRequestsManualSync() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        when(connector.requestRefresh(any(), eq(RefreshMode.INTERACTIVE))).thenReturn(RefreshResult.started(null, null));

        BankConnectionService.RefreshOutcome out = service.refresh(c.getId());

        assertThat(out.authorizationUrl()).isNull();
        assertThat(out.syncRunId()).contains(555L);
        verify(syncTrigger).enqueue(c.getId(), SyncTrigger.MANUAL, "SYSTEM");
    }

    @Test
    void refreshWithWidgetReturnsUrlAndNoSync() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        URI widget = URI.create("https://widget/refresh");
        when(connector.requestRefresh(any(), eq(RefreshMode.INTERACTIVE))).thenReturn(RefreshResult.started(null, widget));

        assertThat(service.refresh(c.getId()).authorizationUrl()).isEqualTo(widget);
        verify(syncTrigger, never()).enqueue(any(), any(), any());
    }

    @Test
    void syncOfDisabledConnectionIsRejected() {
        BankConnectionEntity c = existing(BankConnectionStatus.DISABLED, EXT_ID);
        assertThatThrownBy(() -> service.requestSync(c.getId()))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo(BankConnectionService.DISABLED);
                });
    }

    @Test
    void syncReturnsRunId() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        assertThat(service.requestSync(c.getId())).isEqualTo(555L);
    }

    // ------------------------------------------------------------------ rename / disable / delete

    @Test
    void renameTrimsAndAuditsAndRejectsBlank() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        assertThat(service.rename(c.getId(), "  Nowa nazwa ").getDisplayName()).isEqualTo("Nowa nazwa");
        verify(audit).write(eq(BankConnectionEntity.class), eq(c.getId()), eq("UPDATE"), anyMap(), anyMap());
        assertThatThrownBy(() -> service.rename(c.getId(), "  "))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void disableRevokesAtProviderAndToleratesAlreadyGone() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        doThrow(new ConnectorError(ConnectorErrorClass.REAUTH, "ConnectionNotFound", null, "gone"))
                .when(connector).disable(any());

        assertThat(service.disable(c.getId()).getStatus()).isEqualTo(BankConnectionStatus.DISABLED);
        verify(connector).disable(any());
        verify(audit).write(eq(BankConnectionEntity.class), eq(c.getId()), eq("DISABLE"), anyMap(), anyMap());
        // idempotent
        service.disable(c.getId());
        verify(connector, times(1)).disable(any());
    }

    @Test
    void disableOfNeverAuthorizedConnectionSkipsProvider() {
        BankConnectionEntity c = existing(BankConnectionStatus.PENDING_AUTH, null);
        assertThat(service.disable(c.getId()).getStatus()).isEqualTo(BankConnectionStatus.DISABLED);
        verify(connector, never()).disable(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteWithLinksIsRejected() {
        BankConnectionEntity c = existing(BankConnectionStatus.DISABLED, EXT_ID);
        when(linkDao.count(any(Specification.class))).thenReturn(1L);
        assertThatThrownBy(() -> service.delete(c.getId()))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo(BankConnectionService.HAS_LINKS);
                });
        verify(connectionDao, never()).delete(any(BankConnectionEntity.class));
    }

    @Test
    void deleteOfActiveConnectionIsRejected() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        assertThatThrownBy(() -> service.delete(c.getId()))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(BankConnectionService.NOT_DISABLED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteOfDisabledConnectionWithoutLinksDeletesAndAudits() {
        BankConnectionEntity c = existing(BankConnectionStatus.DISABLED, EXT_ID);
        when(linkDao.count(any(Specification.class))).thenReturn(0L);
        service.delete(c.getId());
        verify(connectionDao).delete(c);
        verify(audit).write(eq(BankConnectionEntity.class), eq(c.getId()), eq("DELETE"), anyMap(), isNull());
    }

    // ------------------------------------------------------------------ system operations

    @Test
    void reauthFailurePublishesEventOnlyOnTransition() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        ConnectorError reauth = new ConnectorError(ConnectorErrorClass.REAUTH, "AispConsentExpired", null, "expired");

        service.recordConnectorFailure(c.getId(), reauth);
        service.recordConnectorFailure(c.getId(), reauth);

        assertThat(c.getStatus()).isEqualTo(BankConnectionStatus.REAUTH_REQUIRED);
        assertThat(c.getLastErrorCode()).isEqualTo("AispConsentExpired");
        verify(events, times(1)).publishEvent(new ReauthRequiredEvent(c.getId()));
    }

    @Test
    void limitFailureKeepsStatusAndOtherFailuresSetError() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        service.recordConnectorFailure(c.getId(), new ConnectorError(ConnectorErrorClass.LIMIT, "limit"));
        assertThat(c.getStatus()).isEqualTo(BankConnectionStatus.ACTIVE);
        service.recordConnectorFailure(c.getId(), new ConnectorError(ConnectorErrorClass.TRANSIENT, "boom"));
        assertThat(c.getStatus()).isEqualTo(BankConnectionStatus.ERROR);
        assertThat(c.getLastErrorCode()).isEqualTo("TRANSIENT");
    }

    @Test
    void sweepMarksExpiringAndPublishesConsentEvent() {
        BankConnectionEntity c = existing(BankConnectionStatus.ACTIVE, EXT_ID);
        c.setExternalConsentId("consent-7");
        c.setConsentExpiresAt(NOW.plus(Duration.ofDays(3)).atOffset(ZoneOffset.UTC));
        when(connectionDao.findByStatusInOrderByIdAsc(any())).thenReturn(List.of(c));

        assertThat(service.recomputeConsentStatuses()).isEqualTo(1);

        assertThat(c.getStatus()).isEqualTo(BankConnectionStatus.CONSENT_EXPIRING);
        verify(events).publishEvent(new ConsentExpiringEvent(c.getId(), "consent-7"));
    }

    @Test
    void purgeRemovesOnlyOldPendingWithoutAccounts() {
        BankConnectionEntity old = existing(BankConnectionStatus.PENDING_AUTH, null);
        old.setCreatedDate(LocalDateTime.ofInstant(NOW.minus(Duration.ofHours(25)), ZoneOffset.UTC));
        BankConnectionEntity fresh = existing(BankConnectionStatus.PENDING_AUTH, null);
        fresh.setCreatedDate(LocalDateTime.ofInstant(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC));
        when(connectionDao.findByStatusInOrderByIdAsc(List.of(BankConnectionStatus.PENDING_AUTH)))
                .thenReturn(List.of(old, fresh));
        when(accountDao.findByConnectionIdOrderByIdAsc(old.getId())).thenReturn(List.of());

        assertThat(service.purgeStalePendingAuth(Duration.ofHours(24))).isEqualTo(1);

        verify(connectionDao).delete(old);
        verify(connectionDao, never()).delete(fresh);
    }
}
