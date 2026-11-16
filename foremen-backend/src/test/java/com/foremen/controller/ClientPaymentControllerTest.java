package com.foremen.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResolver.Completeness;
import com.foremen.config.security.PermissionResource;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.clientpayment.ClientPaymentFilter;
import com.foremen.service.clientpayment.ClientPaymentKind;
import com.foremen.service.clientpayment.ClientPaymentService;
import com.foremen.service.payment.PaymentScheduleService;

/**
 * FOR-11-06 task 2.5 (R1.3, R1.4, R1.7, R3.4): the unified controller is guarded by
 * {@code CLIENT_PAYMENTS}, every handler carries an operation, and the staff actions go through the
 * per-kind checks of the service. Standalone: no Spring context.
 *
 * <p>Validates: Requirements 1.3, 1.4, 1.7, 3.4
 */
class ClientPaymentControllerTest {

    private final ClientPaymentService service = mock(ClientPaymentService.class);
    private final PaymentScheduleService scheduleService = mock(PaymentScheduleService.class);
    private final ClientPaymentController controller = new ClientPaymentController(service, null, null, null,
            scheduleService, null, null, null, null, null, null, null);

    @Test
    void everyHandlerIsCompleteUnderClientPaymentsResource() {
        assertThat(ClientPaymentController.class.getAnnotation(PermissionResource.class).value())
                .isEqualTo("CLIENT_PAYMENTS");
        PermissionResolver resolver = new PermissionResolver();
        int handlers = 0;
        for (Method m : ClientPaymentController.class.getDeclaredMethods()) {
            boolean mapped = Arrays.stream(m.getAnnotations())
                    .anyMatch(a -> a.annotationType().getSimpleName().endsWith("Mapping"));
            if (!mapped) {
                continue;
            }
            handlers++;
            assertThat(m.getAnnotation(PermissionOperation.class)).as(m.getName()).isNotNull();
            assertThat(resolver.classifyCompleteness(new HandlerMethod(controller, m))).as(m.getName())
                    .isEqualTo(Completeness.COMPLETE);
        }
        assertThat(handlers).isEqualTo(24);
    }

    @Test
    void listMapsQueryParametersToTheFilter() throws Exception {
        // a real PageRequest: serializing PageImpl with Pageable.unpaged() throws UnsupportedOperationException
        when(service.find(any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
        mvc.perform(get("/api/client-payments").param("kind", "WORKS").param("status", "OVERDUE")
                .param("projectId", "5").param("dueFrom", "2026-01-01").param("query", "PF"))
                .andExpect(status().isOk());
        ArgumentCaptor<ClientPaymentFilter> captor = ArgumentCaptor.forClass(ClientPaymentFilter.class);
        verify(service).find(any(), captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(5L);
        assertThat(captor.getValue().kind()).isEqualTo(ClientPaymentKind.WORKS);
        assertThat(captor.getValue().dueFrom()).hasToString("2026-01-01");
        assertThat(captor.getValue().query()).isEqualTo("PF");
    }

    @Test
    void staffActionRejectedByTheServiceDoesNotReachTheScheduleService() {
        doThrow(new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied"))
                .when(service).requireWorks();
        assertThatThrownBy(() -> controller.publish(1L)).isInstanceOfSatisfying(ForemenApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(scheduleService);
    }
}
