package com.foremen.service.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.connection.BankConnectorErrors;
import com.foremen.service.bank.connector.BankFeedConnector;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.saltedge.SaltEdgeConnector;

/**
 * Unit tests for {@link SupportedBanksService} (FOR-07-04).
 *
 * <p><b>Validates: Requirements 9.2, 19.9</b>
 */
class SupportedBanksServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");

    private SaltEdgeConnector saltEdge;
    private SupportedBanksService service;

    private static ProviderBank bank(String code, boolean test, String... accountTypes) {
        return new ProviderBank(code, code.toUpperCase(), null, null, "active", "oauth", true,
                Set.of(accountTypes), Set.of("accounts"), true, 90, 1, false, test);
    }

    @BeforeEach
    void setUp() {
        saltEdge = mock(SaltEdgeConnector.class);
        lenient().when(saltEdge.provider()).thenReturn(FeedProvider.SALT_EDGE);
        lenient().when(saltEdge.isAvailable()).thenReturn(true);
        lenient().when(saltEdge.mode()).thenReturn(SaltEdgeMode.CLIENT);
        service = new SupportedBanksService(new BankFeedConnectorRegistry(List.of(saltEdge)), Duration.ofHours(1));
        service.setClock(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void cachesPerProviderModeCountryUntilTtlAndRefreshBypasses() {
        when(saltEdge.listBanks("PL")).thenReturn(List.of(bank("pko_pl", false, "business")));

        SupportedBanksService.SupportedBanks first = service.list(FeedProvider.SALT_EDGE, null, false);
        assertThat(first.country()).isEqualTo("PL");
        assertThat(first.mode()).isEqualTo(SaltEdgeMode.CLIENT);
        assertThat(first.fetchedAt()).isEqualTo(T0);
        assertThat(first.emptyReason()).isNull();

        service.setClock(Clock.fixed(T0.plus(Duration.ofMinutes(59)), ZoneOffset.UTC));
        assertThat(service.list(FeedProvider.SALT_EDGE, "pl", false)).isSameAs(first);
        verify(saltEdge, times(1)).listBanks("PL");

        SupportedBanksService.SupportedBanks refreshed = service.list(FeedProvider.SALT_EDGE, "PL", true);
        assertThat(refreshed.fetchedAt()).isEqualTo(T0.plus(Duration.ofMinutes(59)));
        verify(saltEdge, times(2)).listBanks("PL");

        service.setClock(Clock.fixed(T0.plus(Duration.ofHours(3)), ZoneOffset.UTC));
        service.list(FeedProvider.SALT_EDGE, "PL", false);
        verify(saltEdge, times(3)).listBanks("PL");
    }

    @Test
    void modeIsPartOfTheCacheKey() {
        when(saltEdge.listBanks("PL")).thenReturn(List.of(bank("pko_pl", false, "business")));
        service.list(FeedProvider.SALT_EDGE, "PL", false);
        when(saltEdge.mode()).thenReturn(SaltEdgeMode.PARTNER);
        assertThat(service.list(FeedProvider.SALT_EDGE, "PL", false).mode()).isEqualTo(SaltEdgeMode.PARTNER);
        verify(saltEdge, times(2)).listBanks("PL");
    }

    @Test
    void onlyFakeBanksMeansProviderStatusLimited() {
        when(saltEdge.listBanks("PL")).thenReturn(List.of(bank("fakebank_simple_xf", true, "personal")));
        assertThat(service.list(FeedProvider.SALT_EDGE, "PL", false).emptyReason())
                .isEqualTo(SupportedBanksService.EmptyReason.PROVIDER_STATUS_LIMITED);
    }

    @Test
    void emptyListMeansNoBanks() {
        when(saltEdge.listBanks("PL")).thenReturn(List.of());
        SupportedBanksService.SupportedBanks result = service.list(FeedProvider.SALT_EDGE, "PL", false);
        assertThat(result.banks()).isEmpty();
        assertThat(result.emptyReason()).isEqualTo(SupportedBanksService.EmptyReason.NO_BANKS);
    }

    @Test
    void clientPendingIsAnEmptyLimitedListNotAnError() {
        when(saltEdge.listBanks("PL")).thenThrow(new ConnectorError(ConnectorErrorClass.CONFIG, "ClientPending",
                null, "pending"));
        SupportedBanksService.SupportedBanks result = service.list(FeedProvider.SALT_EDGE, "PL", false);
        assertThat(result.banks()).isEmpty();
        assertThat(result.emptyReason()).isEqualTo(SupportedBanksService.EmptyReason.PROVIDER_STATUS_LIMITED);
    }

    @Test
    void providerFailureMapsToLocalizedError() {
        when(saltEdge.listBanks("PL")).thenThrow(new ConnectorError(ConnectorErrorClass.TRANSIENT, "timeout"));
        assertThatThrownBy(() -> service.list(FeedProvider.SALT_EDGE, "PL", false))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessageCode()).isEqualTo("error.bank.provider.transient");
                });
    }

    @Test
    void unavailableConnectorIsRejected() {
        when(saltEdge.isAvailable()).thenReturn(false);
        assertThatThrownBy(() -> service.list(FeedProvider.SALT_EDGE, "PL", false))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo(BankConnectorErrors.PROVIDER_NOT_AVAILABLE));
        assertThatThrownBy(() -> service.list(FeedProvider.STUB, "PL", false))
                .isInstanceOf(ForemenApiException.class);
    }

    @Test
    void filterBySearchBusinessAndTest() {
        List<ProviderBank> banks = List.of(bank("pko_pl", false, "business", "personal"),
                bank("ing_pl", false, "personal"), bank("fake_business_demobank_xf", true, "business"));
        assertThat(SupportedBanksService.filter(banks, " PKO ", false, true)).extracting(ProviderBank::code)
                .containsExactly("pko_pl");
        assertThat(SupportedBanksService.filter(banks, null, true, true)).extracting(ProviderBank::code)
                .containsExactly("pko_pl", "fake_business_demobank_xf");
        assertThat(SupportedBanksService.filter(banks, "", true, false)).extracting(ProviderBank::code)
                .containsExactly("pko_pl");
    }

    @Test
    void ttlParsingAcceptsBothStyles() {
        assertThat(SupportedBanksService.parseTtl("30m")).isEqualTo(Duration.ofMinutes(30));
        assertThat(SupportedBanksService.parseTtl("PT2H")).isEqualTo(Duration.ofHours(2));
        assertThat(SupportedBanksService.parseTtl("nonsense")).isEqualTo(Duration.ofHours(1));
        assertThat(SupportedBanksService.parseTtl(null)).isEqualTo(Duration.ofHours(1));
    }
}
