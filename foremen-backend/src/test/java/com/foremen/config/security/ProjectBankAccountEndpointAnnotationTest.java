package com.foremen.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.method.HandlerMethod;

import com.foremen.controller.ProjectController;
import com.foremen.controller.model.ProjectBankAccountRequest;
import com.foremen.controller.model.ProjectBankAccountResponse;
import com.foremen.service.ProjectBankAccountService;

/**
 * FOR-11-06 task 3.4 (R4.1, R4.2): {@code PUT /api/projects/{id}/bank-account} is guarded by
 * {@code PROJECTS} UPDATE and delegates to {@link ProjectBankAccountService}.
 */
class ProjectBankAccountEndpointAnnotationTest {

    private static Method handler() throws NoSuchMethodException {
        return ProjectController.class.getMethod("setBankAccount", Long.class, ProjectBankAccountRequest.class);
    }

    @Test
    void handlerIsPutOnBankAccountPath() throws Exception {
        PutMapping mapping = handler().getAnnotation(PutMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/{id}/bank-account");
    }

    @Test
    void handlerResolvesToProjectsUpdateAndIsComplete() throws Exception {
        PermissionResolver resolver = new PermissionResolver();
        HandlerMethod hm = new HandlerMethod(mock(ProjectController.class), handler());

        assertThat(resolver.classifyCompleteness(hm)).isEqualTo(PermissionResolver.Completeness.COMPLETE);
        PermissionResolver.ResolvedPair pair = resolver.resolve(hm);
        assertThat(pair).isNotNull();
        assertThat(pair.resource()).isEqualTo("PROJECTS");
        assertThat(pair.operation()).isEqualTo("UPDATE");
    }

    @Test
    void handlerDelegatesToServiceAndReturnsStoredValue() throws Exception {
        ProjectBankAccountService service = mock(ProjectBankAccountService.class);
        when(service.set(7L, "pl61 1090")).thenReturn("PL611090");
        // Real handler body on a controller whose other collaborators are mocks.
        java.lang.reflect.Constructor<?> ctor = ProjectController.class.getDeclaredConstructors()[0];
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            args[i] = types[i] == ProjectBankAccountService.class ? service
                    : types[i].isInterface() || !java.lang.reflect.Modifier.isFinal(types[i].getModifiers())
                            ? mock(types[i]) : null;
        }
        ProjectController real = (ProjectController) ctor.newInstance(args);

        ResponseEntity<ProjectBankAccountResponse> response =
                real.setBankAccount(7L, new ProjectBankAccountRequest("pl61 1090"));

        verify(service).set(7L, "pl61 1090");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(new ProjectBankAccountResponse(7L, "PL611090"));
    }
}
