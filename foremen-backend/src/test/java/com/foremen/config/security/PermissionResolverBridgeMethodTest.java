package com.foremen.config.security;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge-case unit test for {@link PermissionResolver#resolve(HandlerMethod)} bridge-vs-declared
 * annotation lookup (task 2.6, Requirement 4.2).
 *
 * <p>When a concrete controller implements a generic CRUD interface with a narrowed type argument,
 * the compiler emits a synthetic <em>bridge method</em> on the concrete class. The governing
 * {@link PermissionOperation} lives on the interface's {@code default} method, not on the bridge.
 * This test builds a {@link HandlerMethod} over the concrete controller's inherited CRUD method and
 * asserts {@code resolve} still derives the operation from the interface despite the bridge.</p>
 *
 * <p>The fixtures below are self-contained: {@link CrudFixtureController} is a generic interface
 * whose {@code find(...)} {@code default} method carries {@code @PermissionOperation("READ")}, and
 * {@link ConcreteFixtureController} is a {@code @RestController} carrying
 * {@code @PermissionResource("FIXTURE")} that implements the interface with a narrowed type
 * argument so a bridge method is generated.</p>
 */
class PermissionResolverBridgeMethodTest {

    private final PermissionResolver resolver = new PermissionResolver();

    @Test
    @DisplayName("resolve finds the interface @PermissionOperation through a synthetic bridge method (4.2)")
    void resolvesOperationThroughBridgeMethod() {
        ConcreteFixtureController bean = new ConcreteFixtureController();

        Method bridge = findBridgeFind(ConcreteFixtureController.class);
        assertThat(bridge.isBridge())
                .as("the fixture must actually produce a synthetic bridge method for 'find'")
                .isTrue();

        // Build the HandlerMethod over the concrete controller's inherited CRUD method as presented
        // by the bridge, exactly as Spring MVC would present it for an inherited default method.
        HandlerMethod handlerMethod = new HandlerMethod(bean, bridge);

        PermissionResolver.ResolvedPair pair = resolver.resolve(handlerMethod);

        assertThat(pair)
                .as("resolve must produce a pair even though the annotation lives on the interface's declared method")
                .isNotNull();
        assertThat(pair.resource()).isEqualTo("FIXTURE");
        assertThat(pair.operation())
                .as("the operation must be read from the interface's declared method, not the bridge")
                .isEqualTo("READ");
    }

    @Test
    @DisplayName("the governing @PermissionOperation lives on the interface, not the narrowed override the bridge forwards to (4.2)")
    void annotationLivesOnInterfaceNotOnNarrowedOverride() {
        Method bridge = findBridgeFind(ConcreteFixtureController.class);

        // The bridge forwards to the concrete narrowed override find(FixtureDto); that override
        // carries no @PermissionOperation of its own — the governing annotation is on the
        // interface's declared method. Resolving through the bridge to the interface is what makes
        // the (resource, operation) lookup succeed.
        Method narrowedOverride = BridgeMethodResolver.findBridgedMethod(bridge);
        assertThat(narrowedOverride.getDeclaringClass())
                .as("the bridge must forward to the narrowed override on the concrete controller")
                .isEqualTo(ConcreteFixtureController.class);
        assertThat(narrowedOverride.getDeclaredAnnotation(PermissionOperation.class))
                .as("the concrete narrowed override declares no @PermissionOperation of its own")
                .isNull();

        Method interfaceMethod = findDeclaredFind(CrudFixtureController.class);
        assertThat(interfaceMethod.getDeclaredAnnotation(PermissionOperation.class))
                .as("the interface's declared method is where @PermissionOperation actually lives")
                .isNotNull();
    }

    // --- Helpers ---

    private static Method findBridgeFind(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals("find") && method.isBridge()) {
                return method;
            }
        }
        throw new IllegalStateException("No bridge 'find' method found on " + type.getName());
    }

    private static Method findDeclaredFind(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals("find")) {
                return method;
            }
        }
        throw new IllegalStateException("No declared 'find' method found on " + type.getName());
    }

    // --- Fixtures ---

    /** Payload types used only to force a narrowed generic argument (and thus a bridge method). */
    interface FixturePayload {
    }

    record FixtureDto(String id) implements FixturePayload {
    }

    /**
     * Generic CRUD interface whose {@code default find} method carries the governing
     * {@link PermissionOperation}. The concrete controller narrows {@code T} and overrides
     * {@code find} with the specific type, so the compiler emits a synthetic bridge
     * {@code find(FixturePayload)} that forwards to the narrowed override — and the governing
     * annotation stays on this interface's declared method.
     */
    interface CrudFixtureController<T extends FixturePayload> {

        @PermissionOperation("READ")
        @GetMapping("/fixture/bridge")
        T find(T probe);
    }

    /**
     * Concrete controller carrying {@link PermissionResource}. It overrides {@code find} with the
     * narrowed {@link FixtureDto} type (carrying no {@link PermissionOperation} of its own), so the
     * compiler generates a synthetic bridge {@code find(FixturePayload)} on this class that forwards
     * to this narrowed override — the exact bridge scenario Requirement 4.2 targets.
     */
    @RestController
    @PermissionResource("FIXTURE")
    static class ConcreteFixtureController implements CrudFixtureController<FixtureDto> {

        @Override
        public FixtureDto find(FixtureDto probe) {
            return probe;
        }
    }
}
