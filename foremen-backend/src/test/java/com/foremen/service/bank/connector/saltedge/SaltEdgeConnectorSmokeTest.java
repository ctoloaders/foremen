package com.foremen.service.bank.connector.saltedge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.service.bank.PublicBaseUrl;
import com.foremen.service.bank.connector.AuthorizationStart;
import com.foremen.service.bank.connector.BankConnectionRef;
import com.foremen.service.bank.connector.NormalizedAccountRef;
import com.foremen.service.bank.connector.RefreshMode;
import com.foremen.service.bank.connector.RefreshResult;
import com.foremen.service.bank.connector.TransactionPage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FOR-07-04 task 5.3 — smoke test of {@link SaltEdgeConnector} over a mocked {@link SaltEdgeModeApi}
 * (availability, connect session body, refresh limit, transaction paging). Detailed HTTP-level tests
 * are task 5.4.
 */
class SaltEdgeConnectorSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final PublicBaseUrl BASE = new PublicBaseUrl("https://dev.foremen.eu");

    private final SaltEdgeModeApi api = mock(SaltEdgeModeApi.class);

    private static SaltEdgeProperties props(boolean enabled) {
        return new SaltEdgeProperties(enabled, SaltEdgeMode.CLIENT, null, "app", "secret", null, null, null, null,
                0, true, null, null, null);
    }

    private SaltEdgeConnector connector(boolean enabled) {
        when(api.mode()).thenReturn(SaltEdgeMode.CLIENT);
        return new SaltEdgeConnector(api, props(enabled), BASE, Clock.fixed(NOW, ZoneOffset.UTC), 14);
    }

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    private static BankConnectionRef ref(String externalConnectionId) {
        return new BankConnectionRef(42L, FeedProvider.SALT_EDGE, SaltEdgeMode.CLIENT, null,
                externalConnectionId, null, "fakebank_simple_xf");
    }

    @Test
    void availabilityFollowsConfiguration() {
        assertThat(connector(true).isAvailable()).isTrue();
        assertThat(connector(false).isAvailable()).isFalse();
        assertThat(connector(true).provider()).isEqualTo(FeedProvider.SALT_EDGE);
        assertThat(new SaltEdgeConnector(api, props(true), new PublicBaseUrl(""), Clock.systemUTC(), 14)
                .isAvailable()).isFalse();
    }

    @Test
    void startAuthorizationBuildsSessionFromPublicBaseUrlAndHolderScope() throws Exception {
        SaltEdgeConnector connector = connector(true);
        when(api.ensureIdentity()).thenReturn("cust-1");
        when(api.providers(anyString(), anyBoolean())).thenReturn(List.of(json(
                "{\"code\":\"fakebank_simple_xf\",\"name\":\"Fake Bank\",\"country_code\":\"XF\","
                        + "\"supported_fetch_scopes\":[\"accounts\",\"holder_info\",\"transactions\"]}")));
        when(api.providersWithSaltEdgeKeys(anyString(), anyBoolean())).thenReturn(Optional.of(List.of()));
        when(api.createSession(any())).thenReturn(new SaltEdgeModeApi.SessionUrl(
                "https://www.saltedge.com/connect?token=t", NOW.plusSeconds(3600)));

        AuthorizationStart start = connector.startAuthorization(ref(null), "fakebank_simple_xf", null);

        assertThat(start.authorizationUrl().toString()).isEqualTo("https://www.saltedge.com/connect?token=t");
        assertThat(start.externalCustomerId()).isEqualTo("cust-1");
        ArgumentCaptor<SaltEdgeModeApi.SessionRequest> captor = ArgumentCaptor.forClass(SaltEdgeModeApi.SessionRequest.class);
        verify(api).createSession(captor.capture());
        SaltEdgeModeApi.SessionRequest request = captor.getValue();
        assertThat(request.customerId()).isEqualTo("cust-1");
        assertThat(request.providerCode()).isEqualTo("fakebank_simple_xf");
        assertThat(request.holderInfo()).isTrue();
        assertThat(request.fromDate()).isEqualTo(LocalDate.of(2026, 11, 10).minusDays(89));
        assertThat(request.returnTo()).isEqualTo("https://dev.foremen.eu/bank/connections/return?connectionId=42");
        assertThat(request.customFields()).containsEntry("foremenConnectionId", "42");
        assertThat(request.includeFakeProviders()).isTrue();
    }

    @Test
    void refreshIsNotCalledBeforeNextRefreshPossibleAt() throws Exception {
        SaltEdgeConnector connector = connector(true);
        when(api.connection("c-1")).thenReturn(json(
                "{\"id\":\"c-1\",\"next_refresh_possible_at\":\"2026-11-10T11:00:00Z\"}"));

        RefreshResult result = connector.requestRefresh(ref("c-1"), RefreshMode.INTERACTIVE);

        assertThat(result.started()).isFalse();
        assertThat(result.nextRefreshPossibleAt()).isEqualTo(Instant.parse("2026-11-10T11:00:00Z"));
        verify(api, never()).refreshSession(anyString(), any());
    }

    @Test
    void transactionsReturnPendingWithFirstPageOnlyAndContinueByNextId() throws Exception {
        SaltEdgeConnector connector = connector(true);
        JsonNode posted = json("{\"id\":\"100\",\"status\":\"posted\",\"amount\":-10.5,\"currency_code\":\"PLN\","
                + "\"made_on\":\"2026-11-02\",\"description\":\"Zakup\"}");
        JsonNode pending = json("{\"id\":\"101\",\"status\":\"pending\",\"amount\":-3,\"currency_code\":\"PLN\","
                + "\"made_on\":\"2026-11-09\",\"description\":\"Karta\"}");
        when(api.transactions(eq("c-1"), eq("acc-1"), isNull())).thenReturn(new SaltEdgeClient.Page(List.of(posted), "200"));
        when(api.pendingTransactions("c-1", "acc-1")).thenReturn(List.of(pending));
        when(api.transactions("c-1", "acc-1", "200")).thenReturn(new SaltEdgeClient.Page(List.of(), null));

        TransactionPage first = connector.fetchTransactions(ref("c-1"), new NormalizedAccountRef("acc-1"), null);
        assertThat(first.posted()).extracting(t -> t.externalId()).containsExactly("100");
        assertThat(first.pending()).extracting(t -> t.externalId()).containsExactly("101");
        assertThat(first.complete()).isFalse();

        TransactionPage second = connector.fetchTransactions(ref("c-1"), new NormalizedAccountRef("acc-1"),
                first.nextCursor());
        assertThat(second.pending()).isEmpty();
        assertThat(second.complete()).isTrue();
        assertThat(second.nextCursor()).isNull();
        verify(api).pendingTransactions("c-1", "acc-1");
    }
}
