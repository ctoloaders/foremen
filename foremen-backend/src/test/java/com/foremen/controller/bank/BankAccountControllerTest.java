package com.foremen.controller.bank;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankBalanceGapEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.HolderMatch;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.account.BankAccountService;
import com.foremen.service.bank.cash.CashAccountDto;
import com.foremen.service.bank.cash.CashAccountRequest;
import com.foremen.service.bank.cash.CashLedgerService;
import com.foremen.service.bank.connection.BankConnectionService;

/**
 * FOR-07-04 task 7.1 — {@link BankAccountController}: bank + cash account list (holder warning,
 * computed cash balance; R3.8, R19.5, R27.1), the include toggle / holder override (R3.3, R3.8),
 * balance gaps and acknowledge (R7.3) and the cash register CRUD.
 */
class BankAccountControllerTest {

    private BankAccountService accountService;
    private CashLedgerService cashLedgerService;
    private BankConnectionService connectionService;
    private MockMvc mockMvc;
    private BankConnectionEntity connection;

    @BeforeEach
    void setUp() {
        accountService = mock(BankAccountService.class);
        cashLedgerService = mock(CashLedgerService.class);
        connectionService = mock(BankConnectionService.class);
        ForemenControllerAdvice advice = new ForemenControllerAdvice(new MessageResolver(new StaticMessageSource()));
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new BankAccountController(accountService, cashLedgerService, connectionService))
                .setControllerAdvice(advice).build();
        connection = new BankConnectionEntity();
        connection.setId(7L);
        connection.setProvider(FeedProvider.STUB);
        connection.setBankName("Stub Bank");
        when(connectionService.list()).thenReturn(List.of(connection));
        when(connectionService.get(7L)).thenReturn(connection);
    }

    @Test
    @DisplayName("GET /accounts returns bank accounts (holder warning, bank name) then cash registers (computed balance)")
    void listBankAndCash() throws Exception {
        BankAccountEntity mismatch = bankAccount(70L, HolderMatch.MISMATCH);
        when(accountService.listBankAccounts(null)).thenReturn(List.of(mismatch));
        when(cashLedgerService.listAccounts()).thenReturn(List.of(
                new CashAccountDto(80L, "Kasa", "PLN", new BigDecimal("100.00"), new BigDecimal("350.00"), true, false)));

        mockMvc.perform(get("/api/bank/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].type").value("BANK"))
                .andExpect(jsonPath("$[0].bankName").value("Stub Bank"))
                .andExpect(jsonPath("$[0].holderMatch").value("MISMATCH"))
                .andExpect(jsonPath("$[0].holderWarning").value(true))
                .andExpect(jsonPath("$[0].includedInLedger").value(false))
                .andExpect(jsonPath("$[0].transactionsCursor").doesNotExist())
                .andExpect(jsonPath("$[1].type").value("CASH"))
                .andExpect(jsonPath("$[1].balance").value(350.00))
                .andExpect(jsonPath("$[1].defaultForCash").value(true));
    }

    @Test
    @DisplayName("GET /accounts?connectionId= returns only that connection's bank accounts")
    void listByConnection() throws Exception {
        when(accountService.listBankAccounts(7L)).thenReturn(List.of(bankAccount(70L, HolderMatch.MATCH)));

        mockMvc.perform(get("/api/bank/accounts").param("connectionId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].connectionId").value(7));
        verify(cashLedgerService, never()).listAccounts();
    }

    @Test
    @DisplayName("PATCH /accounts/{id} with a reason overrides the holder mismatch")
    void includeWithOverride() throws Exception {
        BankAccountEntity overridden = bankAccount(70L, HolderMatch.MISMATCH);
        overridden.setIncludedInLedger(true);
        overridden.setHolderOverrideAt(OffsetDateTime.parse("2026-11-02T10:00:00Z"));
        overridden.setHolderOverrideBy("admin@example.com");
        overridden.setHolderOverrideReason("Konto wspólnika");
        when(accountService.setIncludedInLedger(70L, true, "Konto wspólnika")).thenReturn(overridden);

        mockMvc.perform(patch("/api/bank/accounts/70").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includedInLedger\":true,\"holderOverrideReason\":\"Konto wspólnika\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.includedInLedger").value(true))
                .andExpect(jsonPath("$.holderWarning").value(false))
                .andExpect(jsonPath("$.holderOverrideBy").value("admin@example.com"));
    }

    @Test
    @DisplayName("PATCH /accounts/{id} without includedInLedger → 400; missing reason from the service → 400 reasonRequired")
    void includeValidation() throws Exception {
        mockMvc.perform(patch("/api/bank/accounts/70").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BankAccountController.INCLUDED_REQUIRED));
        verify(accountService, never()).setIncludedInLedger(any(), anyBoolean(), any());

        when(accountService.setIncludedInLedger(70L, true, null))
                .thenThrow(new ForemenApiException(HttpStatus.BAD_REQUEST, BankAccountService.REASON_REQUIRED));
        mockMvc.perform(patch("/api/bank/accounts/70").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includedInLedger\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BankAccountService.REASON_REQUIRED));
    }

    @Test
    @DisplayName("balance gaps list + acknowledge reports the account's hasBalanceGap")
    void balanceGaps() throws Exception {
        BankAccountEntity account = bankAccount(70L, HolderMatch.MATCH);
        account.setHasBalanceGap(true);
        BankBalanceGapEntity gap = new BankBalanceGapEntity();
        gap.setId(90L);
        gap.setAccount(account);
        gap.setDifference(new BigDecimal("-900.00"));
        when(accountService.listGaps(70L)).thenReturn(List.of(gap));
        when(accountService.get(70L)).thenReturn(account);

        mockMvc.perform(get("/api/bank/accounts/70/balance-gaps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(90))
                .andExpect(jsonPath("$[0].difference").value(-900.00))
                .andExpect(jsonPath("$[0].hasBalanceGap").value(true));

        BankBalanceGapEntity acked = new BankBalanceGapEntity();
        acked.setId(90L);
        acked.setAccount(account);
        acked.setAcknowledgedBy("admin@example.com");
        acked.setNote("test");
        when(accountService.acknowledgeGap(90L, "test")).thenAnswer(inv -> {
            account.setHasBalanceGap(false);
            return acked;
        });
        mockMvc.perform(post("/api/bank/balance-gaps/90/acknowledge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedBy").value("admin@example.com"))
                .andExpect(jsonPath("$.accountId").value(70))
                .andExpect(jsonPath("$.hasBalanceGap").value(false));
    }

    @Test
    @DisplayName("cash registers: create → 201, archive → 200")
    void cashCrud() throws Exception {
        CashAccountDto created = new CashAccountDto(81L, "Kasa EUR", "EUR", BigDecimal.ZERO, BigDecimal.ZERO, false, false);
        when(cashLedgerService.createAccount(new CashAccountRequest("Kasa EUR", "EUR", null, null, null)))
                .thenReturn(created);
        when(cashLedgerService.archiveAccount(81L))
                .thenReturn(new CashAccountDto(81L, "Kasa EUR", "EUR", BigDecimal.ZERO, BigDecimal.ZERO, false, true));

        mockMvc.perform(post("/api/bank/cash-accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kasa EUR\",\"currency\":\"EUR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(81));
        mockMvc.perform(post("/api/bank/cash-accounts/81/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
    }

    @Test
    @DisplayName("GET /accounts/{id} of a cash register returns the computed balance")
    void getCashViaAccounts() throws Exception {
        BankAccountEntity cash = new BankAccountEntity();
        cash.setId(80L);
        cash.setType(BankAccountType.CASH);
        when(accountService.get(80L)).thenReturn(cash);
        when(cashLedgerService.getAccount(80L)).thenReturn(
                new CashAccountDto(80L, "Kasa", "PLN", BigDecimal.ZERO, new BigDecimal("12.50"), true, false));

        mockMvc.perform(get("/api/bank/accounts/80"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CASH"))
                .andExpect(jsonPath("$.balance").value(12.50));
    }

    private BankAccountEntity bankAccount(Long id, HolderMatch match) {
        BankAccountEntity a = new BankAccountEntity();
        a.setId(id);
        a.setType(BankAccountType.BANK);
        a.setConnection(connection);
        a.setName("Firmowe PLN");
        a.setCurrency("PLN");
        a.setIban("PL61109010140000071219812874");
        a.setHolderMatch(match);
        a.setIncludedInLedger(match != HolderMatch.MISMATCH);
        a.setTransactionsCursor("cursor-xyz");
        return a;
    }
}
