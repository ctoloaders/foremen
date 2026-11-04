package com.foremen.service.bank.connector.saltedge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankSyncRunDao;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.BankSyncRunEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SyncTrigger;
import com.foremen.service.bank.connection.BankConnectionService;
import com.foremen.service.bank.connection.BankSyncTrigger;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.link.BankLedgerAudit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * FOR-07-04 task 7.3 (R10.4–10.9): {@link SaltEdgeCallbackHandler} branch behaviour — resolution,
 * unknown-connection no-op, success / fail / notify / destroy and idempotency.
 */
class SaltEdgeCallbackHandlerTest {

    private BankConnectionDao connectionDao;
    private BankSyncRunDao runDao;
    private BankConnectionService service;
    private BankSyncTrigger trigger;
    private BankLedgerAudit audit;
    private SaltEdgeCallbackHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connectionDao = mock(BankConnectionDao.class);
        runDao = mock(BankSyncRunDao.class);
        service = mock(BankConnectionService.class);
        trigger = mock(BankSyncTrigger.class);
        audit = mock(BankLedgerAudit.class);
        ObjectProvider<BankSyncTrigger> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(trigger);
        when(connectionDao.findByProviderAndExternalConnectionId(any(), anyString())).thenReturn(Optional.empty());
        when(connectionDao.findById(anyLong())).thenReturn(Optional.empty());
        handler = new SaltEdgeCallbackHandler(connectionDao, runDao, service, provider, audit,
                mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("unknown connection → no side effects (R10.8)")
    void unknownConnectionIsNoOp() {
        handler.handle(callback("success", "999", null, null, null));
        handler.handle(callback("fail", null, "77", null, "AispConsentExpired"));
        handler.handle(callback("destroy", "999", "77", null, null));

        verifyNoInteractions(service, trigger, audit, runDao);
    }

    @Test
    @DisplayName("foremenConnectionId with a different customer / provider connection id → no-op")
    void foremenIdWithMismatchIsIgnored() {
        BankConnectionEntity c = connection(42L, BankConnectionStatus.PENDING_AUTH, "cust-A", null);
        when(connectionDao.findById(42L)).thenReturn(Optional.of(c));
        handler.handle(new SaltEdgeCallbackReceived("success", "111", "cust-B", "42", null, null, null));

        BankConnectionEntity other = connection(43L, BankConnectionStatus.ACTIVE, null, "ext-1");
        when(connectionDao.findById(43L)).thenReturn(Optional.of(other));
        handler.handle(new SaltEdgeCallbackReceived("success", "ext-2", null, "43", null, null, null));

        verifyNoInteractions(service, trigger);
    }

    @Test
    @DisplayName("success on PENDING_AUTH (resolved by foremenConnectionId) → completeIfPending with connection_id; no extra run")
    void successCompletesPending() {
        BankConnectionEntity c = connection(42L, BankConnectionStatus.PENDING_AUTH, "cust-A", null);
        when(connectionDao.findById(42L)).thenReturn(Optional.of(c));
        BankConnectionEntity active = connection(42L, BankConnectionStatus.ACTIVE, "cust-A", "111");
        when(service.completeIfPending(eq(42L), any()))
                .thenReturn(new BankConnectionService.CompletionOutcome(active, true, Optional.of(5L)));

        handler.handle(callback("success", "111", "cust-A", "42", null));

        verify(service).completeIfPending(42L, Map.of("connection_id", "111"));
        verifyNoInteractions(trigger);
    }

    @Test
    @DisplayName("success on an ACTIVE connection (resolved by connection_id) → CALLBACK run, repeated → same enqueue (coalesced)")
    void successOnActiveEnqueuesCallbackRun() {
        BankConnectionEntity c = connection(7L, BankConnectionStatus.ACTIVE, "cust", "111");
        when(connectionDao.findByProviderAndExternalConnectionId(FeedProvider.SALT_EDGE, "111"))
                .thenReturn(Optional.of(c));

        handler.handle(callback("success", "111", null, null, null));
        handler.handle(callback("success", "111", null, null, null));

        verify(service, never()).completeIfPending(anyLong(), any());
        verify(trigger, org.mockito.Mockito.times(2)).enqueue(eq(7L), eq(SyncTrigger.CALLBACK), anyString());
    }

    @Test
    @DisplayName("success on a DISABLED connection → ignored")
    void successOnDisabledIgnored() {
        BankConnectionEntity c = connection(7L, BankConnectionStatus.DISABLED, null, "111");
        when(connectionDao.findByProviderAndExternalConnectionId(FeedProvider.SALT_EDGE, "111"))
                .thenReturn(Optional.of(c));
        handler.handle(callback("success", "111", null, null, null));
        verifyNoInteractions(service, trigger);
    }

    @Test
    @DisplayName("fail → recordConnectorFailure with the mapped error class (R10.5)")
    void failRecordsMappedError() {
        BankConnectionEntity c = connection(7L, BankConnectionStatus.ACTIVE, null, "111");
        when(connectionDao.findByProviderAndExternalConnectionId(FeedProvider.SALT_EDGE, "111"))
                .thenReturn(Optional.of(c));

        handler.handle(callback("fail", "111", null, null, "AispConsentExpired"));

        ArgumentCaptor<ConnectorError> error = ArgumentCaptor.forClass(ConnectorError.class);
        verify(service).recordConnectorFailure(eq(7L), error.capture());
        assertThat(error.getValue().getErrorClass()).isEqualTo(ConnectorErrorClass.REAUTH);
        verifyNoInteractions(trigger);
    }

    @Test
    @DisplayName("notify → stage on the latest run only; same stage again → no write (R10.6, R10.9)")
    void notifyRecordsStage() {
        BankConnectionEntity c = connection(7L, BankConnectionStatus.ACTIVE, null, "111");
        when(connectionDao.findByProviderAndExternalConnectionId(FeedProvider.SALT_EDGE, "111"))
                .thenReturn(Optional.of(c));
        BankSyncRunEntity run = new BankSyncRunEntity();
        run.setId(3L);
        when(runDao.findByConnectionIdOrderByIdDesc(eq(7L), any(Pageable.class))).thenReturn(List.of(run));

        handler.handle(new SaltEdgeCallbackReceived("notify", "111", null, null, "fetch_accounts", null, null));
        assertThat(run.getStage()).isEqualTo("fetch_accounts");
        verify(runDao).save(run);

        handler.handle(new SaltEdgeCallbackReceived("notify", "111", null, null, "fetch_accounts", null, null));
        verify(runDao, org.mockito.Mockito.times(1)).save(any());
        verifyNoInteractions(service, trigger);
    }

    @Test
    @DisplayName("destroy → DISABLED + audit; repeated destroy → no further change (R10.7, R10.9)")
    void destroyDisables() {
        BankConnectionEntity c = connection(7L, BankConnectionStatus.ACTIVE, null, "111");
        when(connectionDao.findByProviderAndExternalConnectionId(FeedProvider.SALT_EDGE, "111"))
                .thenReturn(Optional.of(c));
        when(connectionDao.findById(7L)).thenReturn(Optional.of(c));

        handler.handle(callback("destroy", "111", null, null, null));
        handler.handle(callback("destroy", "111", null, null, null));

        assertThat(c.getStatus()).isEqualTo(BankConnectionStatus.DISABLED);
        verify(connectionDao, org.mockito.Mockito.times(1)).save(c);
        verify(audit, org.mockito.Mockito.times(1)).write(eq(BankConnectionEntity.class), eq(7L), eq("DISABLE"), any(), any());
        verifyNoInteractions(service, trigger);
    }

    // ---- helpers --------------------------------------------------------------------------

    private static SaltEdgeCallbackReceived callback(String type, String connectionId, String customerId,
                                                     String foremenId, String errorClass) {
        return new SaltEdgeCallbackReceived(type, connectionId, customerId, foremenId, null, errorClass, null);
    }

    private static BankConnectionEntity connection(Long id, BankConnectionStatus status, String customerId,
                                                   String externalId) {
        BankConnectionEntity c = new BankConnectionEntity();
        c.setId(id);
        c.setProvider(FeedProvider.SALT_EDGE);
        c.setStatus(status);
        c.setExternalCustomerId(customerId);
        c.setExternalConnectionId(externalId);
        return c;
    }
}
