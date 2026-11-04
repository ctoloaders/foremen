package com.foremen.controller.bank;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.dao.BankSyncRunAccountDao;
import com.foremen.dao.BankSyncRunDao;
import com.foremen.dao.model.SyncRunStatus;
import com.foremen.dao.model.SyncTrigger;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.sync.BankSyncRunDto;
import com.foremen.service.bank.sync.BankSyncRunQueryService;

/**
 * FOR-07-04 task 7.1 — {@link BankSyncRunController} (R5.1, R19.6): filtered pageable history
 * (newest first by default) and run detail; {@link BankSyncRunQueryService} period validation.
 */
class BankSyncRunControllerTest {

    private BankSyncRunQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(BankSyncRunQueryService.class);
        ForemenControllerAdvice advice = new ForemenControllerAdvice(new MessageResolver(new StaticMessageSource()));
        mockMvc = MockMvcBuilders.standaloneSetup(new BankSyncRunController(queryService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(advice).build();
    }

    @Test
    @DisplayName("GET /sync-runs passes filters and defaults to id DESC")
    void listPassesFilters() throws Exception {
        when(queryService.list(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(run(5L, null)), Pageable.ofSize(20), 1));

        mockMvc.perform(get("/api/bank/sync-runs").param("connectionId", "7")
                        .param("status", "FAILED").param("status", "PARTIAL")
                        .param("from", "2026-11-01").param("to", "2026-11-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(5))
                .andExpect(jsonPath("$.content[0].accountsSeen").value(2));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(queryService).list(eq(7L), eq(List.of(SyncRunStatus.FAILED, SyncRunStatus.PARTIAL)),
                eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 30)), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("GET /sync-runs/{id} returns the per-account breakdown")
    void detail() throws Exception {
        when(queryService.get(5L)).thenReturn(run(5L, List.of(new BankSyncRunDto.AccountBreakdown(
                1L, 70L, "Firmowe PLN", "PL61109010140000071219812874", "PLN", SyncRunStatus.SUCCEEDED, 3, 0, 0, null))));

        mockMvc.perform(get("/api/bank/sync-runs/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.accounts[0].accountId").value(70))
                .andExpect(jsonPath("$.accounts[0].transactionsNew").value(3));
    }

    @Test
    @DisplayName("an unknown run → 404 from the service")
    void unknownRun() throws Exception {
        when(queryService.get(99L)).thenThrow(new ForemenApiException(HttpStatus.NOT_FOUND, BankSyncRunQueryService.NOT_FOUND));
        mockMvc.perform(get("/api/bank/sync-runs/99")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("BankSyncRunQueryService: from > to → 422 error.bank.syncRun.periodInvalid, no query")
    void periodInvalid() {
        BankSyncRunDao runDao = mock(BankSyncRunDao.class);
        BankSyncRunQueryService service = new BankSyncRunQueryService(runDao, mock(BankSyncRunAccountDao.class));
        assertThatThrownBy(() -> service.list(null, null, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 1),
                Pageable.ofSize(20)))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(422);
                    assertThat(e.getMessageCode()).isEqualTo(BankSyncRunQueryService.PERIOD_INVALID);
                });
        verifyNoInteractions(runDao);
    }

    private static BankSyncRunDto run(Long id, List<BankSyncRunDto.AccountBreakdown> accounts) {
        return new BankSyncRunDto(id, 7L, "Stub 1", "Stub Bank", SyncTrigger.MANUAL, SyncRunStatus.SUCCEEDED,
                null, null, null, null, 2, 3, 0, 0, 0, 1, null, null, null, "admin@example.com", accounts);
    }
}
