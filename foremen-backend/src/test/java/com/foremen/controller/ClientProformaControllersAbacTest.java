package com.foremen.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResolver.Completeness;
import com.foremen.config.security.RequiresPermission;
import com.foremen.dao.ClientProformaDao;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.proforma.ClientProformaClientService;
import com.foremen.service.procurement.proforma.ClientProformaService;
import com.foremen.service.procurement.proforma.ProformaCancelService;
import com.foremen.service.procurement.proforma.ProformaIssueService;
import com.foremen.service.procurement.proforma.ProformaPdfService;

/**
 * FOR-11-03 task 3.5 (R1.6, R6.1, R6.5, R6.6): every handler of both proforma controllers carries an
 * explicit {@code @RequiresPermission}, is classified COMPLETE, the staff endpoints refuse a CLIENT, and
 * the client endpoints hide existence from a non-CLIENT.
 *
 * <p>Validates: Requirements 1.6, 6.1, 6.5, 6.6
 */
class ClientProformaControllersAbacTest {

    private final PermissionResolver resolver = new PermissionResolver();
    private final ClientProformaService staffService = mock(ClientProformaService.class);
    private final ClientProformaClientService clientService = mock(ClientProformaClientService.class);
    private final ClientProformaController staff = new ClientProformaController(staffService,
            mock(ProformaIssueService.class), mock(ProformaCancelService.class), mock(ProformaPdfService.class),
            mock(ClientProformaDao.class));
    private final ClientProformaClientController client = new ClientProformaClientController(clientService);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                7L, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void staffHandlersAreExplicitCompleteAndMapToExpectedOperations() {
        Map<String, String> expected = Map.of("find", "READ", "findById", "READ", "pdf", "READ",
                "create", "CREATE", "update", "UPDATE", "delete", "DELETE", "issue", "UPDATE",
                "cancel", "UPDATE");
        int handlers = 0;
        for (Method m : ClientProformaController.class.getDeclaredMethods()) {
            RequiresPermission rp = m.getAnnotation(RequiresPermission.class);
            if (rp == null) {
                continue;
            }
            handlers++;
            assertThat(resolver.classifyCompleteness(new HandlerMethod(staff, m))).as(m.getName())
                    .isEqualTo(Completeness.COMPLETE);
            assertThat(rp.resource()).isEqualTo("CLIENT_PROFORMAS");
            assertThat(rp.operation()).as(m.getName()).isEqualTo(expected.get(m.getName()));
        }
        assertThat(handlers).isEqualTo(expected.size());
    }

    @Test
    void clientHandlersAreExplicitReadOnly() {
        int handlers = 0;
        for (Method m : ClientProformaClientController.class.getDeclaredMethods()) {
            RequiresPermission rp = m.getAnnotation(RequiresPermission.class);
            if (rp == null) {
                continue;
            }
            handlers++;
            assertThat(resolver.classifyCompleteness(new HandlerMethod(client, m))).as(m.getName())
                    .isEqualTo(Completeness.COMPLETE);
            assertThat(rp.resource()).isEqualTo("CLIENT_PROFORMAS");
            assertThat(rp.operation()).isEqualTo("READ");
        }
        assertThat(handlers).isEqualTo(3);
        assertThat(ClientProformaClientController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/client-proformas/client");
    }

    @Test
    void everyPublicHandlerMethodIsAnnotated() {
        for (Class<?> c : List.of(ClientProformaController.class, ClientProformaClientController.class)) {
            for (Method m : c.getDeclaredMethods()) {
                boolean mapped = java.util.Arrays.stream(m.getAnnotations()).anyMatch(a -> a.annotationType()
                        .getSimpleName().endsWith("Mapping"));
                if (mapped) {
                    assertThat(m.getAnnotation(RequiresPermission.class)).as(c.getSimpleName() + "." + m.getName())
                            .isNotNull();
                }
            }
        }
    }

    @Test
    void staffEndpointsRefuseAClientWith403() {
        login("CLIENT");
        assertThatThrownBy(() -> staff.findById(1L)).isInstanceOfSatisfying(ForemenApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> staff.find(null, null)).isInstanceOf(ForemenApiException.class);
        assertThatThrownBy(() -> staff.pdf(1L)).isInstanceOf(ForemenApiException.class);
        assertThatThrownBy(() -> staff.delete(1L)).isInstanceOf(ForemenApiException.class);
        verifyNoInteractions(staffService);
    }

    @Test
    void clientEndpointsAnswer404ForANonClientWithoutTouchingTheService() {
        login("MANAGER");
        assertThatThrownBy(() -> client.get(1L)).isInstanceOfSatisfying(ForemenApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.list(1L)).isInstanceOf(ForemenApiException.class);
        assertThatThrownBy(() -> client.pdf(1L)).isInstanceOf(ForemenApiException.class);
        verifyNoInteractions(clientService);
    }
}
