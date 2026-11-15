package com.foremen.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResolver.Completeness;
import com.foremen.config.security.RequiresPermission;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.externalorder.ExternalOrderLifecycleService;
import com.foremen.service.procurement.externalorder.ExternalOrderService;

/**
 * FOR-11-03 task 4.5 (R1.6, R15.7, R21.2): every handler of {@link ExternalOrderController} carries an
 * explicit {@code @RequiresPermission} on {@code EXTERNAL_ORDERS}, is classified COMPLETE, the financier
 * actions use APPROVE, and a caller outside the money audience is refused with 403.
 *
 * <p>Validates: Requirements 1.6, 15.7, 21.2
 */
class ExternalOrderControllerAbacTest {

    private final PermissionResolver resolver = new PermissionResolver();
    private final ExternalOrderService service = mock(ExternalOrderService.class);
    private final ExternalOrderLifecycleService lifecycle = mock(ExternalOrderLifecycleService.class);
    private final ProcurementProjectAccess access = mock(ProcurementProjectAccess.class, CALLS_REAL_METHODS);
    private final ExternalOrderController controller = new ExternalOrderController(service, lifecycle, access);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                7L, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void handlersAreExplicitCompleteAndMapToExpectedOperations() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("find", "READ"), Map.entry("findById", "READ"), Map.entry("create", "CREATE"),
                Map.entry("update", "UPDATE"), Map.entry("replaceLines", "UPDATE"),
                Map.entry("replaceDocuments", "UPDATE"), Map.entry("delete", "DELETE"),
                Map.entry("send", "UPDATE"), Map.entry("cancel", "UPDATE"),
                Map.entry("markPaid", "APPROVE"), Map.entry("returnToBuyer", "APPROVE"),
                Map.entry("unmarkPaid", "APPROVE"));
        int handlers = 0;
        for (Method m : ExternalOrderController.class.getDeclaredMethods()) {
            RequiresPermission rp = m.getAnnotation(RequiresPermission.class);
            if (rp == null) {
                continue;
            }
            handlers++;
            assertThat(resolver.classifyCompleteness(new HandlerMethod(controller, m))).as(m.getName())
                    .isEqualTo(Completeness.COMPLETE);
            assertThat(rp.resource()).isEqualTo("EXTERNAL_ORDERS");
            assertThat(rp.operation()).as(m.getName()).isEqualTo(expected.get(m.getName()));
        }
        assertThat(handlers).isEqualTo(expected.size());
    }

    @Test
    void everyMappedMethodIsAnnotated() {
        for (Method m : ExternalOrderController.class.getDeclaredMethods()) {
            boolean mapped = java.util.Arrays.stream(m.getAnnotations())
                    .anyMatch(a -> a.annotationType().getSimpleName().endsWith("Mapping"));
            if (mapped) {
                assertThat(m.getAnnotation(RequiresPermission.class)).as(m.getName()).isNotNull();
            }
        }
    }

    @Test
    void callerOutsideTheMoneyAudienceGets403WithoutTouchingServices() {
        login("CLIENT");
        assertThatThrownBy(() -> controller.findById(1L)).isInstanceOfSatisfying(ForemenApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controller.find(null, null)).isInstanceOf(ForemenApiException.class);
        assertThatThrownBy(() -> controller.send(1L)).isInstanceOf(ForemenApiException.class);
        assertThatThrownBy(() -> controller.markPaid(1L, null)).isInstanceOf(ForemenApiException.class);
        verifyNoInteractions(service, lifecycle);
    }

    @Test
    void financierAndBuyerReachTheirActions() {
        login("FINANCIER");
        controller.markPaid(5L, new ExternalOrderController.MarkPaidRequest(LocalDate.of(2026, 1, 2), "ok"));
        verify(lifecycle).markPaid(5L, LocalDate.of(2026, 1, 2), "ok");
        controller.returnToBuyer(5L, new ExternalOrderController.ReturnRequest("wrong amount"));
        verify(lifecycle).returnToBuyer(5L, "wrong amount");
        controller.unmarkPaid(5L);
        verify(lifecycle).unmarkPaid(5L);

        login("BUYER");
        controller.send(6L);
        verify(lifecycle).send(6L);
        controller.cancel(6L, null);
        verify(lifecycle).cancel(6L);
        controller.findById(6L);
        verify(service).findById(6L);
    }
}
