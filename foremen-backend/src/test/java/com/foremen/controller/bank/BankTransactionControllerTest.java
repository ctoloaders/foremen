package com.foremen.controller.bank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.LinkStatus;
import com.foremen.service.bank.cash.CashDirection;
import com.foremen.service.bank.cash.CashEntryOutcome;
import com.foremen.service.bank.cash.CashEntryRequest;
import com.foremen.service.bank.cash.CashLedgerService;
import com.foremen.service.bank.ledger.BankLedgerBulkService;
import com.foremen.service.bank.ledger.BankLedgerQueryService;
import com.foremen.service.bank.ledger.LedgerFilter;
import com.foremen.service.bank.ledger.LedgerFilter.DetectionFilter;
import com.foremen.service.bank.ledger.LedgerFilter.LedgerStatusFilter;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.bank.link.LinkOutcome;
import com.foremen.service.bank.link.LinkRequest;
import com.foremen.service.permission.ForemenPermissionEvaluator;

/**
 * FOR-07-04 task 7.2 — {@link BankTransactionController} key endpoints: filter binding (multi-valued
 * {@code linkStatus} incl. {@code TO_REVIEW}, {@code detection}, {@code channel}, {@code transactionId};
 * R20.2–R20.4), the ADMIN-only payload flag (R21.6), manual link creation (R17.1), "create payout" with the
 * extra {@code PAYROLL}/CREATE check (R16.2, R21.4), manual cash entry (R27.3) and the
 * {@code payoutStaysPaid} warning on link removal (R16.8).
 */
class BankTransactionControllerTest {

    private BankLedgerQueryService queryService;
    private BankLinkService linkService;
    private CashLedgerService cashLedgerService;
    private ForemenPermissionEvaluator permissionEvaluator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(BankLedgerQueryService.class);
        linkService = mock(BankLinkService.class);
        cashLedgerService = mock(CashLedgerService.class);
        permissionEvaluator = mock(ForemenPermissionEvaluator.class);
        BankLedgerBulkService bulkService = mock(BankLedgerBulkService.class);
        ForemenControllerAdvice advice = new ForemenControllerAdvice(new MessageResolver(new StaticMessageSource()));
        mockMvc = MockMvcBuilders.standaloneSetup(new BankTransactionController(
                        queryService, bulkService, linkService, cashLedgerService, permissionEvaluator))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(advice).build();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String roleCode) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "user@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_" + roleCode))));
    }

    @Test
    @DisplayName("GET /transactions binds every filter param incl. multi-valued linkStatus (TO_REVIEW) and detection")
    void listBindsFilter() throws Exception {
        mockMvc.perform(get("/api/bank/transactions")
                        .param("from", "2026-01-01").param("to", "2026-01-31")
                        .param("channel", "CASH").param("direction", "EXPENSE")
                        .param("linkStatus", "TO_REVIEW").param("linkStatus", "UNLINKED")
                        .param("detection", "AI").param("detection", "NONE")
                        .param("kind", "WORKER_PAYOUT").param("projectId", "9")
                        .param("amountMin", "10.50").param("q", "kowalski")
                        .param("includePending", "false").param("transactionId", "77")
                        .param("page", "1").param("size", "25"))
                .andExpect(status().isOk());

        ArgumentCaptor<LedgerFilter> filter = ArgumentCaptor.forClass(LedgerFilter.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(queryService).list(filter.capture(), page.capture());
        LedgerFilter f = filter.getValue();
        assertThat(f.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(f.to()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(f.channel()).isEqualTo(LedgerChannel.CASH);
        assertThat(f.direction()).isEqualTo(LedgerFilter.LedgerDirection.EXPENSE);
        assertThat(f.linkStatuses()).containsExactly(LedgerStatusFilter.TO_REVIEW, LedgerStatusFilter.UNLINKED);
        assertThat(f.detections()).containsExactly(DetectionFilter.AI, DetectionFilter.NONE);
        assertThat(f.kind()).isEqualTo(LinkKind.WORKER_PAYOUT);
        assertThat(f.projectId()).isEqualTo(9L);
        assertThat(f.amountMin()).isEqualByComparingTo("10.50");
        assertThat(f.q()).isEqualTo("kowalski");
        assertThat(f.includePending()).isFalse();
        assertThat(f.transactionId()).isEqualTo(77L);
        assertThat(page.getValue().getPageNumber()).isEqualTo(1);
        assertThat(page.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("GET /transactions/{id} passes includeProviderPayload=true only for ADMIN")
    void detailAdminFlag() throws Exception {
        authenticateAs("ADMIN");
        mockMvc.perform(get("/api/bank/transactions/5")).andExpect(status().isOk());
        verify(queryService).detail(5L, true);

        authenticateAs("FINANCIER");
        mockMvc.perform(get("/api/bank/transactions/6")).andExpect(status().isOk());
        verify(queryService).detail(6L, false);
    }

    @Test
    @DisplayName("POST /transactions/{id}/links → 201 LinkOutcome, remember defaults to true")
    void createLink() throws Exception {
        when(linkService.createLink(eq(3L), any(LinkRequest.class), anyBoolean()))
                .thenReturn(new LinkOutcome(3L, 30L, LinkStatus.LINKED, false));

        mockMvc.perform(post("/api/bank/transactions/3/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PROCUREMENT\",\"amount\":120.00,\"procurementDocumentId\":44}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkId").value(30))
                .andExpect(jsonPath("$.linkStatus").value("LINKED"))
                .andExpect(jsonPath("$.payoutStaysPaid").value(false));

        ArgumentCaptor<LinkRequest> req = ArgumentCaptor.forClass(LinkRequest.class);
        verify(linkService).createLink(eq(3L), req.capture(), eq(true));
        assertThat(req.getValue().kind()).isEqualTo(LinkKind.PROCUREMENT);
        assertThat(req.getValue().procurementDocumentId()).isEqualTo(44L);
        assertThat(req.getValue().amount()).isEqualByComparingTo(new BigDecimal("120.00"));
    }

    @Test
    @DisplayName("DELETE /links/{id} surfaces the payoutStaysPaid warning")
    void removeLinkWarning() throws Exception {
        when(linkService.removeLink(30L)).thenReturn(new LinkOutcome(3L, null, LinkStatus.UNLINKED, true));
        mockMvc.perform(delete("/api/bank/links/30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutStaysPaid").value(true));
    }

    @Test
    @DisplayName("POST /transactions/{id}/payout without PAYROLL/CREATE → 403, service not called")
    void createPayoutForbiddenWithoutPayroll() throws Exception {
        authenticateAs("MANAGER");
        when(permissionEvaluator.isAllowed("MANAGER", "PAYROLL", "CREATE")).thenReturn(false);

        mockMvc.perform(post("/api/bank/transactions/3/payout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":12,\"amount\":500}"))
                .andExpect(status().isForbidden());
        verify(linkService, never()).createPayoutFromLine(anyLong(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("POST /transactions/{id}/payout with PAYROLL/CREATE → 201 and delegates to the link service")
    void createPayoutAllowed() throws Exception {
        authenticateAs("FINANCIER");
        when(permissionEvaluator.isAllowed("FINANCIER", "PAYROLL", "CREATE")).thenReturn(true);
        when(linkService.createPayoutFromLine(eq(3L), eq(12L), any(), eq("note"), eq(false)))
                .thenReturn(new LinkOutcome(3L, 31L, LinkStatus.LINKED, false));

        mockMvc.perform(post("/api/bank/transactions/3/payout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":12,\"amount\":500,\"note\":\"note\",\"remember\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkId").value(31));
    }

    @Test
    @DisplayName("POST /transactions/cash → 201 and delegates to CashLedgerService.createManual")
    void createCashEntry() throws Exception {
        when(cashLedgerService.createManual(any(CashEntryRequest.class)))
                .thenReturn(new CashEntryOutcome(90L, 8L, new BigDecimal("-50.00"), LinkStatus.UNLINKED, null));

        mockMvc.perform(post("/api/bank/transactions/cash").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":8,\"direction\":\"EXPENSE\",\"amount\":50.00,"
                                + "\"date\":\"2026-02-03\",\"title\":\"Taxi\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value(90));

        ArgumentCaptor<CashEntryRequest> req = ArgumentCaptor.forClass(CashEntryRequest.class);
        verify(cashLedgerService).createManual(req.capture());
        assertThat(req.getValue().direction()).isEqualTo(CashDirection.EXPENSE);
        assertThat(req.getValue().date()).isEqualTo(LocalDate.of(2026, 2, 3));
        assertThat(req.getValue().link()).isNull();
    }
}
