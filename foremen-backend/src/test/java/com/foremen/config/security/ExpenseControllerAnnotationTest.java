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

import com.foremen.controller.ExpenseController;

/**
 * FOR-07-03 task 4.4 (Requirement 10.3; entity-creation-rules step 3) - {@code ExpenseController} is
 * fully annotated for {@code EXPENSE_UPLOAD}: the real {@link PermissionResolver} classifies every
 * HTTP handler {@code COMPLETE} (what {@code PermissionAnnotationValidator} checks at startup) and
 * resolves it to the expected pair.
 */
class ExpenseControllerAnnotationTest {

    private final PermissionResolver resolver = new PermissionResolver();

    @Test
    void everyHandlerIsCompleteAndMapsToExpenseUpload() {
        assertThat(ExpenseController.class.getAnnotation(PermissionResource.class).value())
                .isEqualTo("EXPENSE_UPLOAD");
        ExpenseController bean = new ExpenseController(null, null);
        Map<String, String> expected = Map.of(
                "create", "CREATE", "options", "READ", "status", "READ");
        int handlers = 0;
        for (Method m : ExpenseController.class.getDeclaredMethods()) {
            if (!isHandler(m)) {
                continue;
            }
            handlers++;
            HandlerMethod hm = new HandlerMethod(bean, m);
            assertThat(resolver.classifyCompleteness(hm))
                    .as(m.getName()).isEqualTo(PermissionResolver.Completeness.COMPLETE);
            PermissionResolver.ResolvedPair pair = resolver.resolve(hm);
            assertThat(pair).as("%s must be guarded", m.getName()).isNotNull();
            assertThat(pair.resource()).isEqualTo("EXPENSE_UPLOAD");
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
