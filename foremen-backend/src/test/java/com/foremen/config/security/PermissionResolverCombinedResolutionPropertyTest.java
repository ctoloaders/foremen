package com.foremen.config.security;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for {@link PermissionResolver#resolve(HandlerMethod)} covering the combined
 * resolution path (task 2.2).
 *
 * <p>The fixtures below emulate the real controller shape: a bean type carries
 * {@link PermissionResource}{@code (R)} and its (interface-inherited) methods carry
 * {@link PermissionOperation}{@code (O)} with <b>no</b> {@link RequiresPermission}. Because
 * annotation members must be compile-time constants they cannot be generated at runtime, so the
 * property draws {@code (R, O)} pairs from a matrix of fixture controllers/interfaces with known
 * declared values and asserts the resolver reconstructs exactly that pair.
 */
class PermissionResolverCombinedResolutionPropertyTest {

    private final PermissionResolver resolver = new PermissionResolver();

    // ------------------------------------------------------------------
    // Property 1: Combined resolution derives resource from the class and operation from the method
    // ------------------------------------------------------------------

    // Feature: FOR-03-08-api-protection, Property 1: Combined resolution derives resource from the
    // class and operation from the method. For any handler whose bean type carries
    // @PermissionResource(R) and whose declaring (interface) method carries @PermissionOperation(O)
    // and no @RequiresPermission, resolve(handlerMethod) returns ResolvedPair(R, O).
    /**
     * <b>Validates: Requirements 1.3, 2.3, 4.1, 4.3, 10.1</b>
     */
    @Property(tries = 100)
    void combinedResolutionDerivesResourceFromClassAndOperationFromMethod(
            @ForAll("combinedFixtures") Fixture fixture) {

        HandlerMethod handlerMethod = new HandlerMethod(fixture.bean(), fixture.method());

        PermissionResolver.ResolvedPair pair = resolver.resolve(handlerMethod);

        assertThat(pair)
                .as("A bean type with @PermissionResource(%s) and a method with @PermissionOperation(%s) "
                        + "(no @RequiresPermission) must resolve to that exact pair",
                        fixture.expectedResource(), fixture.expectedOperation())
                .isEqualTo(new PermissionResolver.ResolvedPair(
                        fixture.expectedResource(), fixture.expectedOperation()));
    }

    // ------------------------------------------------------------------
    // Fixture generation
    // ------------------------------------------------------------------

    /** A generated combined-resolution case: the bean plus the handler method and its expected pair. */
    record Fixture(Object bean, Method method, String expectedResource, String expectedOperation) {
    }

    /**
     * Enumerates every (controller, operation-method) combination across the fixture controllers so
     * every declared resource is paired with every declared operation. Each element carries the
     * expected {@code (R, O)} pair read from the annotations.
     */
    @Provide
    Arbitrary<Fixture> combinedFixtures() {
        List<Fixture> fixtures = new ArrayList<>();
        for (ControllerSpec spec : CONTROLLER_SPECS) {
            for (String methodName : OPERATION_METHODS) {
                try {
                    Method method = spec.controllerClass().getMethod(methodName);
                    Object bean = spec.controllerClass().getDeclaredConstructor().newInstance();
                    fixtures.add(new Fixture(bean, method, spec.expectedResource(),
                            OPERATION_VALUES.get(methodName)));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Failed to build fixture for "
                            + spec.controllerClass() + "#" + methodName, e);
                }
            }
        }
        return Arbitraries.of(fixtures);
    }

    private record ControllerSpec(Class<?> controllerClass, String expectedResource) {
    }

    private static final List<ControllerSpec> CONTROLLER_SPECS = List.of(
            new ControllerSpec(UsersFixtureController.class, "USERS"),
            new ControllerSpec(RolesFixtureController.class, "ROLES"),
            new ControllerSpec(AuditFixtureController.class, "AUDIT"));

    private static final List<String> OPERATION_METHODS = List.of("create", "read", "update", "delete");

    private static final java.util.Map<String, String> OPERATION_VALUES = java.util.Map.of(
            "create", "CREATE",
            "read", "READ",
            "update", "UPDATE",
            "delete", "DELETE");

    /**
     * A CRUD interface whose {@code default} methods carry {@link PermissionOperation}, mirroring the
     * real {@code AdminController} shape so the annotation is inherited by implementors and read via a
     * bridge/declared-method lookup.
     */
    private interface CrudFixtureInterface {

        @PermissionOperation("CREATE")
        default void create() {
        }

        @PermissionOperation("READ")
        default void read() {
        }

        @PermissionOperation("UPDATE")
        default void update() {
        }

        @PermissionOperation("DELETE")
        default void delete() {
        }
    }

    @PermissionResource("USERS")
    private static class UsersFixtureController implements CrudFixtureInterface {
    }

    @PermissionResource("ROLES")
    private static class RolesFixtureController implements CrudFixtureInterface {
    }

    @PermissionResource("AUDIT")
    private static class AuditFixtureController implements CrudFixtureInterface {
    }
}
