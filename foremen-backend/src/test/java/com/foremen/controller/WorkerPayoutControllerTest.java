package com.foremen.controller;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.service.payroll.WorkerPayoutService;
import com.foremen.service.payroll.dto.PayoutFilter;
import com.foremen.service.payroll.dto.PayoutLedgerEntryDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;

/**
 * FOR-07-04 task 7.6 (Requirement 16.3) — {@link WorkerPayoutController} responses expose the
 * payout's linked ledger lines ({@code ledgerEntries}) end-to-end in the JSON, and an unlinked payout
 * serializes an empty array (never {@code null}). Standalone MockMvc, mocked service.
 */
class WorkerPayoutControllerTest {

    private WorkerPayoutService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(WorkerPayoutService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkerPayoutController(service)).build();
    }

    @Test
    @DisplayName("GET /api/worker-payouts exposes ledgerEntries (linked → entries, unlinked → [])")
    void listExposesLedgerEntries() throws Exception {
        when(service.list(any(PayoutFilter.class))).thenReturn(List.of(
                payout(1L, List.of(new PayoutLedgerEntryDto(701L, "BANK", null, "mBank PLN",
                        new BigDecimal("250.00")))),
                payout(2L, null)));

        mockMvc.perform(get("/api/worker-payouts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ledgerEntries[0].transactionId").value(701))
                .andExpect(jsonPath("$[0].ledgerEntries[0].channel").value("BANK"))
                .andExpect(jsonPath("$[0].ledgerEntries[0].accountName").value("mBank PLN"))
                .andExpect(jsonPath("$[0].ledgerEntries[0].amount").value(250.00))
                .andExpect(jsonPath("$[1].ledgerEntries").isArray())
                .andExpect(jsonPath("$[1].ledgerEntries").isEmpty());
    }

    @Test
    @DisplayName("POST /api/worker-payouts/{id}/pay echoes ledgerEntries")
    void payExposesLedgerEntries() throws Exception {
        when(service.markPaid(1L)).thenReturn(payout(1L, List.of(
                new PayoutLedgerEntryDto(702L, "CASH", null, "Cash PLN", new BigDecimal("250.00")))));

        mockMvc.perform(post("/api/worker-payouts/1/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerEntries[0].transactionId").value(702))
                .andExpect(jsonPath("$.ledgerEntries[0].channel").value("CASH"));
    }

    private static WorkerPayoutDto payout(Long id, List<PayoutLedgerEntryDto> entries) {
        return new WorkerPayoutDto(id, 5L, "Worker", null, null, null,
                new BigDecimal("250.00"), new BigDecimal("250.00"), BigDecimal.ZERO,
                PaymentChannel.BANK_TRANSFER, PayoutStatus.PAID, false, null, null, entries);
    }
}
