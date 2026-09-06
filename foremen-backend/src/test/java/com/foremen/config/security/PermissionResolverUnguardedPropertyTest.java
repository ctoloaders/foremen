package com.foremen.config.security;

import java.lang.reflect.Method;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the Unguarded fallback of {@link PermissionResolver}.
 *
 * <p>Property 3: a handler that carries no permission declaration at all — no
 * {@link RequiresPermission} on the method, no {@link PermissionResource} on its bean type, and no
 * {@link PermissionOperation} on its declaring method — resolves to Unguarded (i.e. {@code resolve}
 * returns {@code null}). This drives {@link PermissionResolver#resolve} directly over fixture
 * {@link HandlerMethod} instances built via reflection, rather than booting a Spring context.
 *
 * Property 3: No declaration at all resolves to Unguarded — Validates: Requirements 5.1, 10.4
 */
@Tag("Feature: FOR-03-08-api-protection, Property 3: No declaration at all resolves to Unguarded")
class PermissionResolverUnguardedPropertyTest {

    private final PermissionResolver resolver = new PermissionResolver();

    // Feature: FOR-03-08-api-protection, Property 3: No declaration at all resolves to Unguarded
    // For every handler whose bean type has no @PermissionResource, whose declaring method has no
    // @PermissionOperation, and whose method has no @RequiresPermission, resolve returns null
    // (the handler is classified as Unguarded and proceeds without a matrix check).
    // Validates: Requirements 5.1, 10.4
    @Property(tries = 100)
    void undeclaredHandlerResolvesToNull(@ForAll("unguardedHandlers") HandlerMethod handlerMethod) {
        assertThat(resolver.resolve(handlerMethod))
                .as("a handler with no permission declaration at all must resolve to Unguarded (null)")
                .isNull();
    }

    // --- Providers ---

    /**
     * Every REST-mapped handler method on the unannotated fixture controllers below. Each fixture
     * controller carries no {@link PermissionResource}, and none of its methods carry
     * {@link PermissionOperation} or {@link RequiresPermission}, so every produced
     * {@link HandlerMethod} is Unguarded.
     */
    @Provide
    Arbitrary<HandlerMethod> unguardedHandlers() {
        return Arbitraries.of(handlerMethods());
    }

    private static List<HandlerMethod> handlerMethods() {
        return List.of(
                handlerMethod(new UnannotatedController(), "list"),
                handlerMethod(new UnannotatedController(), "create"),
                handlerMethod(new AnotherUnannotatedController(), "read"),
                handlerMethod(new AnotherUnannotatedController(), "remove"));
    }

    private static HandlerMethod handlerMethod(Object bean, String methodName) {
        Method method = findMethod(bean.getClass(), methodName);
        return new HandlerMethod(bean, method);
    }

    private static Method findMethod(Class<?> type, String methodName) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new IllegalStateException("Fixture method not found: " + type.getName() + "#" + methodName);
    }

    // --- Fixtures (no @PermissionResource, no @PermissionOperation, no @RequiresPermission) ---

    @RestController
    static class UnannotatedController {

        @GetMapping("/fixture/unguarded/list")
        public String list() {
            return "list";
        }

        @PostMapping("/fixture/unguarded/create")
        public String create() {
            return "create";
        }
    }

    @RestController
    static class AnotherUnannotatedController {

        @GetMapping("/fixture/unguarded/read")
        public String read() {
            return "read";
        }

        @PostMapping("/fixture/unguarded/remove")
        public String remove() {
            return "remove";
        }
    }
}
