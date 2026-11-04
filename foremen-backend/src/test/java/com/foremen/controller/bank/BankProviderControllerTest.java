package com.foremen.controller.bank;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.dao.model.FeedProvider;
import com.foremen.service.bank.account.SupportedBanksService;
import com.foremen.service.bank.account.SupportedBanksService.EmptyReason;
import com.foremen.service.bank.account.SupportedBanksService.SupportedBanks;
import com.foremen.service.bank.connection.BankConnectorErrors;
import com.foremen.service.bank.connector.BankFeedConnector;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.connector.CredentialField;
import com.foremen.service.bank.connector.ProviderBank;

/**
 * FOR-07-04 task 7.1 — {@link BankProviderController}: available providers with credential field
 * descriptors (R2.2, R4.5) and the filtered supported-bank catalogue with {@code fetchedAt} /
 * {@code emptyReason} (R9.2, R19.9).
 */
class BankProviderControllerTest {

    private SupportedBanksService supportedBanksService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BankFeedConnector stub = mock(BankFeedConnector.class);
        when(stub.provider()).thenReturn(FeedProvider.STUB);
        when(stub.isAvailable()).thenReturn(true);
        when(stub.credentialFields()).thenReturn(List.of(new CredentialField("apiKey", "bank.credentials.apiKey", true)));
        BankFeedConnector saltEdge = mock(BankFeedConnector.class);
        when(saltEdge.provider()).thenReturn(FeedProvider.SALT_EDGE);
        when(saltEdge.isAvailable()).thenReturn(false);
        supportedBanksService = mock(SupportedBanksService.class);
        ForemenControllerAdvice advice = new ForemenControllerAdvice(new MessageResolver(new StaticMessageSource()));
        mockMvc = MockMvcBuilders.standaloneSetup(new BankProviderController(
                        new BankFeedConnectorRegistry(List.of(stub, saltEdge)), supportedBanksService))
                .setControllerAdvice(advice).build();
    }

    @Test
    @DisplayName("GET /providers lists only available providers with their credential fields")
    void providers() throws Exception {
        mockMvc.perform(get("/api/bank/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].provider").value("STUB"))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].credentialsRequired").value(true))
                .andExpect(jsonPath("$[0].credentialFields[0].name").value("apiKey"))
                .andExpect(jsonPath("$[0].credentialFields[0].secret").value(true));
    }

    @Test
    @DisplayName("GET /providers/{p}/banks applies q / businessOnly / includeTest and passes refresh")
    void banksFiltered() throws Exception {
        ProviderBank business = bank("pko", "PKO BP", Set.of("business", "personal"), false);
        ProviderBank personal = bank("ing", "ING", Set.of("personal"), false);
        ProviderBank fake = bank("fake_oauth", "Fake Bank", Set.of("business"), true);
        Instant fetchedAt = Instant.parse("2026-11-02T10:00:00Z");
        when(supportedBanksService.list(FeedProvider.STUB, "PL", true)).thenReturn(new SupportedBanks(
                FeedProvider.STUB, null, "PL", List.of(business, personal, fake), fetchedAt, null));

        mockMvc.perform(get("/api/bank/providers/STUB/banks")
                        .param("country", "PL").param("businessOnly", "true")
                        .param("includeTest", "false").param("refresh", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.banks", hasSize(1)))
                .andExpect(jsonPath("$.banks[0].code").value("pko"))
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.country").value("PL"))
                .andExpect(jsonPath("$.fetchedAt").exists());
    }

    @Test
    @DisplayName("an empty catalogue is not an error: emptyReason is returned")
    void emptyReason() throws Exception {
        when(supportedBanksService.list(FeedProvider.STUB, null, false)).thenReturn(new SupportedBanks(
                FeedProvider.STUB, null, "PL", List.of(), Instant.now(), EmptyReason.NO_BANKS));

        mockMvc.perform(get("/api/bank/providers/STUB/banks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.banks", hasSize(0)))
                .andExpect(jsonPath("$.emptyReason").value("NO_BANKS"));
    }

    @Test
    @DisplayName("an unavailable provider → 409 error.bank.provider.notAvailable")
    void providerNotAvailable() throws Exception {
        when(supportedBanksService.list(FeedProvider.SALT_EDGE, null, false))
                .thenThrow(BankConnectorErrors.providerNotAvailable());

        mockMvc.perform(get("/api/bank/providers/SALT_EDGE/banks"))
                .andExpect(status().is(HttpStatus.CONFLICT.value()))
                .andExpect(jsonPath("$.message").value(BankConnectorErrors.PROVIDER_NOT_AVAILABLE));
    }

    private static ProviderBank bank(String code, String name, Set<String> types, boolean test) {
        return new ProviderBank(code, name, null, null, "active", "oauth", true, types, Set.of(), true,
                90, 1, false, test);
    }
}
