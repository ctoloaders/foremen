package com.foremen.controller;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.service.payroll.PayrollLineService;
import com.foremen.service.payroll.PayrollProfileService;
import com.foremen.service.payroll.PayrollReadModelService;
import com.foremen.service.payroll.dto.CompanyPayrollDto;
import com.foremen.service.payroll.dto.PayrollProfileDto;

/**
 * FOR-07-06 task 6.2 — {@link PayrollController}: ABAC mapping / startup completeness of every
 * handler (R14.1) and the controller's error contract — {@code from > to} → 422
 * {@code error.payroll.period.invalid} (R10.1, R11.1), non-numeric {@code openingBalance} /
 * {@code amount} → 422 field codes (R9.2, R8.3), {@code workerIds} as repeated param or comma list.
 *
 * <p>Standalone MockMvc with the real controller + real {@link ForemenControllerAdvice}; the message
 * source falls back to the code, so the response {@code message} equals the error code.
 */
class PayrollControllerTest {

    private PayrollReadModelService readModelService;
    private PayrollLineService lineService;
    private PayrollProfileService profileService;
    private MockMvc mockMvc;
    private final PermissionResolver resolver = new PermissionResolver();

    @BeforeEach
    void setUp() {
        readModelService = mock(PayrollReadModelService.class);
        lineService = mock(PayrollLineService.class);
        profileService = mock(PayrollProfileService.class);
        ForemenControllerAdvice advice = new ForemenControllerAdvice(new MessageResolver(new StaticMessageSource()));
        PayrollController controller = new PayrollController(readModelService, lineService, profileService, advice);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build();
    }

    // --- ABAC (R14.1) --------------------------------------------------------------------------

    @Test
    @DisplayName("class carries @PermissionResource(\"PAYROLL\"); every handler maps per the design and is COMPLETE")
    void everyHandlerIsGuardedPerDesign() throws Exception {
        assertThat(PayrollController.class.getAnnotation(PermissionResource.class).value()).isEqualTo("PAYROLL");
        assertPair("company", "READ");
        assertPair("card", "READ");
        assertPair("hints", "READ");
        assertPair("createLine", "CREATE");
        assertPair("updateLine", "UPDATE");
        assertPair("deleteLine", "DELETE");
        assertPair("setOpeningBalance", "UPDATE");

        PayrollController bare = new PayrollController(null, null, null, null);
        List<Method> handlers = Arrays.stream(PayrollController.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isAnnotationPresent(ExceptionHandler.class))
                .filter(m -> org.springframework.core.annotation.AnnotatedElementUtils
                        .hasAnnotation(m, RequestMapping.class))
                .toList();
        assertThat(handlers).hasSize(7);
        for (Method m : handlers) {
            assertThat(resolver.classifyCompleteness(new HandlerMethod(bare, m)))
                    .as("handler %s must be COMPLETE", m.getName())
                    .isEqualTo(PermissionResolver.Completeness.COMPLETE);
        }
    }

    private void assertPair(String methodName, String operation) {
        Method method = Arrays.stream(PayrollController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName)).findFirst().orElseThrow();
        assertThat(resolver.resolve(new HandlerMethod(new PayrollController(null, null, null, null), method)))
                .as(methodName)
                .isEqualTo(new PermissionResolver.ResolvedPair("PAYROLL", operation));
    }

    // --- Period validation (R10.1, R11.1) ------------------------------------------------------

    @Test
    @DisplayName("from > to → 422 error.payroll.period.invalid on table and card; services not called")
    void periodInvalid() throws Exception {
        mockMvc.perform(get("/api/payroll/company").param("from", "2026-08-01").param("to", "2026-07-01"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("error.payroll.period.invalid"));
        mockMvc.perform(get("/api/payroll/workers/5/card").param("from", "2026-08-01").param("to", "2026-07-01"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("error.payroll.period.invalid"));
        verifyNoInteractions(readModelService);
    }

    @Test
    @DisplayName("workerIds accepted as a comma list and as a repeated param")
    void workerIdsBinding() throws Exception {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(readModelService.companyTable(any(), any(), any()))
                .thenReturn(new CompanyPayrollDto(from, to, List.of(), null));
        mockMvc.perform(get("/api/payroll/company").param("from", "2026-07-01").param("to", "2026-07-31")
                        .param("workerIds", "3,4"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/payroll/company").param("from", "2026-07-01").param("to", "2026-07-31")
                        .param("workerIds", "3").param("workerIds", "4"))
                .andExpect(status().isOk());
        verify(readModelService, org.mockito.Mockito.times(2)).companyTable(from, to, List.of(3L, 4L));
    }

    // --- Non-numeric bodies (R9.2, R8.3) -------------------------------------------------------

    @Test
    @DisplayName("openingBalance \"abc\" → 422 error.payrollProfile.openingBalance.invalid")
    void openingBalanceNonNumeric() throws Exception {
        mockMvc.perform(put("/api/payroll/workers/5/opening-balance")
                        .contentType("application/json").content("{\"openingBalance\":\"abc\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("error.payrollProfile.openingBalance.invalid"));
        verifyNoInteractions(profileService);
    }

    @Test
    @DisplayName("numeric openingBalance reaches the service")
    void openingBalanceNumeric() throws Exception {
        when(profileService.setOpeningBalance(eq(5L), any()))
                .thenReturn(new PayrollProfileDto(5L, new BigDecimal("-12.50")));
        mockMvc.perform(put("/api/payroll/workers/5/opening-balance")
                        .contentType("application/json").content("{\"openingBalance\":-12.50}"))
                .andExpect(status().isOk());
        verify(profileService).setOpeningBalance(5L, new BigDecimal("-12.50"));
    }

    @Test
    @DisplayName("line amount \"abc\" → 422 error.payrollLine.amount.invalid on POST and PUT")
    void lineAmountNonNumeric() throws Exception {
        String body = "{\"workerId\":5,\"month\":\"2026-07-15\",\"name\":\"X\",\"amount\":\"abc\"}";
        mockMvc.perform(post("/api/payroll/lines").contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("error.payrollLine.amount.invalid"));
        mockMvc.perform(put("/api/payroll/lines/9").contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("error.payrollLine.amount.invalid"));
        verifyNoInteractions(lineService);
    }
}
