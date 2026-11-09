package com.foremen.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.method.HandlerMethod;

import com.foremen.controller.DashboardWidgetController;

/**
 * FOR-10 task 2.7 (entity-creation-rules step 3): every DashboardWidgetController handler is
 * COMPLETE for DASHBOARD_WIDGETS, so PermissionAnnotationValidator passes at startup.
 * <b>Validates: Requirements 4.3, 11.2</b>
 */
class DashboardWidgetControllerAnnotationTest {

    private final PermissionResolver resolver = new PermissionResolver();

    @Test
    void everyHandlerIsCompleteAndMapsToDashboardWidgets() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("list", "READ"), Map.entry("datasets", "READ"), Map.entry("get", "READ"),
                Map.entry("create", "CREATE"), Map.entry("update", "UPDATE"),
                Map.entry("activate", "UPDATE"), Map.entry("deactivate", "UPDATE"),
                Map.entry("delete", "DELETE"), Map.entry("validate", "READ"), Map.entry("preview", "READ"),
                Map.entry("aiDraft", "CREATE"));
        DashboardWidgetController bean = new DashboardWidgetController(null, null, null, null);
        int handlers = 0;
        for (Method m : DashboardWidgetController.class.getDeclaredMethods()) {
            if (!isHandler(m)) {
                continue;
            }
            handlers++;
            HandlerMethod hm = new HandlerMethod(bean, m);
            assertThat(resolver.classifyCompleteness(hm)).as(m.getName())
                    .isEqualTo(PermissionResolver.Completeness.COMPLETE);
            PermissionResolver.ResolvedPair pair = resolver.resolve(hm);
            assertThat(pair.resource()).isEqualTo("DASHBOARD_WIDGETS");
            assertThat(pair.operation()).as(m.getName()).isEqualTo(expected.get(m.getName()));
        }
        assertThat(handlers).isEqualTo(expected.size());
    }

    private static boolean isHandler(Method m) {
        return m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class)
                || m.isAnnotationPresent(PutMapping.class) || m.isAnnotationPresent(DeleteMapping.class);
    }
}
