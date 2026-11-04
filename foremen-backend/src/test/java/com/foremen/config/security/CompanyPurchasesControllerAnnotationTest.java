package com.foremen.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.method.HandlerMethod;

import com.foremen.controller.CompanyPurchasesController;

/**
 * FOR-07-03 task 4.5 (Requirement 10.3; entity-creation-rules step 3) - {@code CompanyPurchasesController}
 * is fully annotated for {@code COMPANY_PURCHASES}: the real {@link PermissionResolver} classifies every
 * HTTP handler {@code COMPLETE} and resolves it to the expected operation.
 */
class CompanyPurchasesControllerAnnotationTest {

    private final PermissionResolver resolver = new PermissionResolver();

    @Test
    void everyHandlerIsCompleteAndMapsToCompanyPurchases() {
        assertThat(CompanyPurchasesController.class.getAnnotation(PermissionResource.class).value())
                .isEqualTo("COMPANY_PURCHASES");
        CompanyPurchasesController bean = new CompanyPurchasesController(null);
        Map<String, String> expected = Map.of(
                "listDocuments", "READ", "getDocument", "READ", "listLines", "READ",
                "reprocess", "UPDATE", "relink", "UPDATE", "delete", "DELETE");
        int handlers = 0;
        for (Method m : CompanyPurchasesController.class.getDeclaredMethods()) {
            if (!isHandler(m)) {
                continue;
            }
            handlers++;
            HandlerMethod hm = new HandlerMethod(bean, m);
            assertThat(resolver.classifyCompleteness(hm))
                    .as(m.getName()).isEqualTo(PermissionResolver.Completeness.COMPLETE);
            PermissionResolver.ResolvedPair pair = resolver.resolve(hm);
            assertThat(pair).as("%s must be guarded", m.getName()).isNotNull();
            assertThat(pair.resource()).isEqualTo("COMPANY_PURCHASES");
            assertThat(pair.operation()).as(m.getName()).isEqualTo(expected.get(m.getName()));
        }
        assertThat(handlers).isEqualTo(expected.size());
    }

    private static boolean isHandler(Method m) {
        return m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class)
                || m.isAnnotationPresent(PutMapping.class) || m.isAnnotationPresent(PatchMapping.class)
                || m.isAnnotationPresent(DeleteMapping.class);
    }
}
