package com.foremen.controller.bank;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.FeedProvider;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.account.BankAccountService;
import com.foremen.service.bank.connection.BankConnectionService;
import com.foremen.service.bank.connection.BankConnectionService.AuthorizationOutcome;
import com.foremen.service.bank.connection.BankConnectionService.CompletionOutcome;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.secret.BankConnectionSecretService;
import com.foremen.service.bank.secret.BankConnectionSecretService.CredentialsStatus;

/**
 * FOR-07-04 task 7.1 — {@link BankConnectionController}: lifecycle happy paths, status codes
 * (201 / 202 / 204), secret-free DTOs (R2.3, R21.6) and service error propagation.
 */
class BankConnectionControllerTest {

    private BankConnectionService connectionService;
    private BankConnectionSecretService secretService;
    private BankAccountService accountService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        connectionService = mock(BankConnectionService.class);
        secretService = mock(BankConnectionSecretService.class);
        accountService = mock(BankAccountService.class);
        BankFeedConnectorRegistry registry = new BankFeedConnectorRegistry(List.of());
        ForemenControllerAdvice advice = new ForemenControllerAdvice(new MessageResolver(new StaticMessageSource()));
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new BankConnectionController(connectionService, secretService, accountService, registry))
                .setControllerAdvice(advice).build();
        when(secretService.status(any())).thenReturn(CredentialsStatus.NOT_SET);
    }

    @Test
    @DisplayName("POST /connections → 201 {connection, authorizationUrl}; no provider ids / secrets exposed")
    void createReturnsAuthorization() throws Exception {
        BankConnectionEntity c = connection(7L, BankConnectionStatus.PENDING_AUTH);
        when(connectionService.create(FeedProvider.STUB, null, "Stub 1"))
                .thenReturn(new AuthorizationOutcome(c, URI.create("http://localhost:3000/bank/connections/return?connectionId=7&stub=1")));
        when(accountService.listBankAccounts(7L)).thenReturn(List.of());

        mockMvc.perform(post("/api/bank/connections").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"STUB\",\"displayName\":\"Stub 1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.connection.id").value(7))
                .andExpect(jsonPath("$.connection.status").value("PENDING_AUTH"))
                .andExpect(jsonPath("$.connection.credentialsSet").value(false))
                .andExpect(jsonPath("$.connection.accounts", hasSize(0)))
                .andExpect(jsonPath("$.authorizationUrl").value(containsString("connectionId=7")))
                .andExpect(content().string(not(containsString("externalCustomerId"))))
                .andExpect(content().string(not(containsString("cust-secret"))))
                .andExpect(content().string(not(containsString("holderNames"))));
    }

    @Test
    @DisplayName("GET /connections lists with accounts count; GET /{id} includes accounts")
    void listAndDetail() throws Exception {
        BankConnectionEntity c = connection(7L, BankConnectionStatus.ACTIVE);
        BankAccountEntity a = new BankAccountEntity();
        a.setId(70L);
        a.setType(BankAccountType.BANK);
        a.setConnection(c);
        a.setName("Firmowe PLN");
        a.setCurrency("PLN");
        a.setTransactionsCursor("cursor-xyz");
        when(connectionService.list()).thenReturn(List.of(c));
        when(connectionService.get(7L)).thenReturn(c);
        when(accountService.listBankAccounts(null)).thenReturn(List.of(a));
        when(accountService.listBankAccounts(7L)).thenReturn(List.of(a));
        when(secretService.status(7L)).thenReturn(new CredentialsStatus(true, OffsetDateTime.parse("2026-11-01T10:00:00Z")));

        mockMvc.perform(get("/api/bank/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountsCount").value(1))
                .andExpect(jsonPath("$[0].credentialsSet").value(true))
                .andExpect(jsonPath("$[0].accounts").doesNotExist());
        mockMvc.perform(get("/api/bank/connections/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].name").value("Firmowe PLN"))
                .andExpect(jsonPath("$.accounts[0].bankName").value("Stub Bank"))
                .andExpect(content().string(not(containsString("cursor-xyz"))));
    }

    @Test
    @DisplayName("complete passes the return params; sync → 202 {runId}; delete → 204")
    void completeSyncDelete() throws Exception {
        BankConnectionEntity c = connection(7L, BankConnectionStatus.ACTIVE);
        when(connectionService.complete(7L, Map.of("connectionId", "7")))
                .thenReturn(new CompletionOutcome(c, true, Optional.of(55L)));
        when(connectionService.requestSync(7L)).thenReturn(56L);

        mockMvc.perform(post("/api/bank/connections/7/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"connectionId\":\"7\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connection.status").value("ACTIVE"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.syncRunId").value(55));
        mockMvc.perform(post("/api/bank/connections/7/sync"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(56));
        mockMvc.perform(delete("/api/bank/connections/7"))
                .andExpect(status().isNoContent());
        verify(connectionService).delete(7L);
    }

    @Test
    @DisplayName("PUT /credentials returns only the status, never the values")
    void credentialsAreWriteOnly() throws Exception {
        when(secretService.replace(eq(7L), eq(Map.of("apiKey", "s3cr3t"))))
                .thenReturn(new CredentialsStatus(true, OffsetDateTime.parse("2026-11-01T10:00:00Z")));

        mockMvc.perform(put("/api/bank/connections/7/credentials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"apiKey\":\"s3cr3t\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialsSet").value(true))
                .andExpect(content().string(not(containsString("s3cr3t"))));
    }

    @Test
    @DisplayName("service validation errors keep their code and status (rename invalid → 400, sync disabled → 409)")
    void serviceErrorsPropagate() throws Exception {
        when(connectionService.rename(eq(7L), isNull()))
                .thenThrow(new ForemenApiException(HttpStatus.BAD_REQUEST, BankConnectionService.DISPLAY_NAME_INVALID));
        when(connectionService.requestSync(8L))
                .thenThrow(new ForemenApiException(HttpStatus.CONFLICT, BankConnectionService.DISABLED));

        mockMvc.perform(patch("/api/bank/connections/7").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BankConnectionService.DISPLAY_NAME_INVALID));
        mockMvc.perform(post("/api/bank/connections/8/sync"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(BankConnectionService.DISABLED));
    }

    private static BankConnectionEntity connection(Long id, BankConnectionStatus status) {
        BankConnectionEntity c = new BankConnectionEntity();
        c.setId(id);
        c.setProvider(FeedProvider.STUB);
        c.setStatus(status);
        c.setBankName("Stub Bank");
        c.setDisplayName("Stub 1");
        c.setExternalCustomerId("cust-secret");
        c.setHolderNames(Map.of("taxIds", List.of("5250000000")));
        return c;
    }
}
