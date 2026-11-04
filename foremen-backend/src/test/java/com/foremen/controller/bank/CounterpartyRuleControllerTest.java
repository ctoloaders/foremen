package com.foremen.controller.bank;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.dao.model.LinkKind;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.link.CounterpartyRuleDto;
import com.foremen.service.bank.link.CounterpartyRuleRequest;
import com.foremen.service.bank.link.CounterpartyRuleService;

/**
 * FOR-07-04 task 7.1 — {@link CounterpartyRuleController} (R17.4, R19.7): list / create (201) /
 * delete (204) and service validation codes.
 */
class CounterpartyRuleControllerTest {

    private CounterpartyRuleService ruleService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ruleService = mock(CounterpartyRuleService.class);
        ForemenControllerAdvice advice = new ForemenControllerAdvice(new MessageResolver(new StaticMessageSource()));
        mockMvc = MockMvcBuilders.standaloneSetup(new CounterpartyRuleController(ruleService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(advice).build();
    }

    @Test
    @DisplayName("list / create → 201 / delete → 204")
    void crud() throws Exception {
        CounterpartyRuleDto rule = new CounterpartyRuleDto(3L, "IBAN:PL27114020040000300201355387", "Jan Kowalski",
                LinkKind.WORKER_PAYOUT, 11L, "Jan Kowalski", null, null, null, null, null, null, null, null,
                true, 0, null, null);
        when(ruleService.list(any())).thenReturn(new PageImpl<>(List.of(rule), Pageable.ofSize(20), 1));
        when(ruleService.create(any(CounterpartyRuleRequest.class))).thenReturn(rule);

        mockMvc.perform(get("/api/bank/counterparty-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].counterpartyKey").value("IBAN:PL27114020040000300201355387"));
        mockMvc.perform(post("/api/bank/counterparty-rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartyKey\":\"IBAN:PL27114020040000300201355387\","
                                + "\"targetKind\":\"WORKER_PAYOUT\",\"workerId\":11}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.targetKind").value("WORKER_PAYOUT"));
        mockMvc.perform(delete("/api/bank/counterparty-rules/3")).andExpect(status().isNoContent());
        verify(ruleService).delete(3L);
    }

    @Test
    @DisplayName("a duplicate key → 409 error.bank.rule.duplicateKey")
    void duplicateKey() throws Exception {
        when(ruleService.create(any(CounterpartyRuleRequest.class)))
                .thenThrow(new ForemenApiException(HttpStatus.CONFLICT, CounterpartyRuleService.DUPLICATE_KEY));

        mockMvc.perform(post("/api/bank/counterparty-rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartyKey\":\"NAME:JAN KOWALSKI\",\"targetKind\":\"OTHER_INCOME\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(CounterpartyRuleService.DUPLICATE_KEY));
    }
}
