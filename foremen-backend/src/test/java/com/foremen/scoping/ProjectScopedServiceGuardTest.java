package com.foremen.scoping;

import com.foremen.service.AdminService;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.ReadOnlyAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflection guard test locking the by-id CRUD override set on
 * {@link ProjectScopedService} (Requirements 7.5, 8.5).
 *
 * <p>Every {@code Mutating_Crud_Method} — a single-entity / by-id read or mutation inherited
 * from {@link AdminService} / {@link ReadOnlyAdminService} — MUST be overridden on
 * {@link ProjectScopedService} with a matching {@code default} method, so that no by-id read or
 * mutation can reach the inherited behavior without first passing {@code assertProjectAccess}.
 * The eight guarded methods are {@code findById}, {@code update}, {@code updateAll},
 * {@code updateSingleField}, {@code deleteById}, {@code deleteAll}, {@code softDelete}, and
 * {@code setPropertiesToNull}.</p>
 *
 * <p>This test fails fast if the CRUD framework later grows a mutating by-id method that
 * {@link ProjectScopedService} does not override, or if an existing override is removed.
 * {@code create} is the documented exception: it resolves no existing id and is intentionally
 * left inheriting the unchanged behavior, so this test asserts it is NOT overridden.</p>
 */
@DisplayName("ProjectScopedService by-id CRUD override guard (Req 7.5, 8.5)")
class ProjectScopedServiceGuardTest {

    /**
     * The by-id CRUD methods that MUST be overridden on {@link ProjectScopedService}, matched by
     * name + parameter types against the declarations on {@link AdminService} /
     * {@link ReadOnlyAdminService}.
     */
    private static final String[] MUTATING_CRUD_METHOD_NAMES = {
            "findById",
            "update",
            "updateAll",
            "updateSingleField",
            "deleteById",
            "deleteAll",
            "softDelete",
            "setPropertiesToNull",
    };

    @Test
    @DisplayName("every by-id Mutating_Crud_Method is overridden with a default on ProjectScopedService")
    void everyMutatingCrudMethodIsOverridden() {
        // Enumerate the by-id CRUD methods declared on AdminService/ReadOnlyAdminService.
        Set<Method> guardedMethods = collectMutatingCrudMethods();

        // The design enumerates exactly eight such methods; assert we found them all so a missing
        // declaration (e.g. a renamed framework method) is caught rather than silently skipped.
        assertThat(guardedMethods)
                .as("expected the eight enumerated by-id CRUD methods on AdminService/ReadOnlyAdminService")
                .hasSize(MUTATING_CRUD_METHOD_NAMES.length);

        for (Method guarded : guardedMethods) {
            Optional<Method> override = findDeclaredOverride(guarded);
            assertThat(override)
                    .as("ProjectScopedService must declare a matching override for %s(%s)",
                            guarded.getName(), parameterTypeNames(guarded))
                    .isPresent();

            Method declared = override.orElseThrow();
            assertThat(declared.isDefault())
                    .as("ProjectScopedService.%s(%s) must be a default override",
                            declared.getName(), parameterTypeNames(declared))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("create is intentionally NOT overridden on ProjectScopedService")
    void createIsNotOverridden() {
        boolean createDeclaredOnProjectScoped = Arrays.stream(ProjectScopedService.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("create"));

        assertThat(createDeclaredOnProjectScoped)
                .as("create resolves no existing id and must inherit the unchanged behavior "
                        + "(CREATE-path validation is out of scope)")
                .isFalse();
    }

    /**
     * Collects the enumerated by-id CRUD methods by name from {@link AdminService} and
     * {@link ReadOnlyAdminService}, matching on the method name only (each name is unique across
     * the two contracts). Returns the actual declaring-interface {@link Method} objects so their
     * parameter types drive the override match.
     */
    private static Set<Method> collectMutatingCrudMethods() {
        Set<String> names = Set.of(MUTATING_CRUD_METHOD_NAMES);
        Set<Method> result = new LinkedHashSet<>();

        for (Class<?> contract : new Class<?>[]{AdminService.class, ReadOnlyAdminService.class}) {
            for (Method m : contract.getDeclaredMethods()) {
                if (names.contains(m.getName()) && !m.isSynthetic() && !Modifier.isStatic(m.getModifiers())) {
                    result.add(m);
                }
            }
        }
        return result;
    }

    /**
     * Finds a method DECLARED (not merely inherited) on {@link ProjectScopedService} that matches
     * the given method by name + parameter types.
     */
    private static Optional<Method> findDeclaredOverride(Method target) {
        return Arrays.stream(ProjectScopedService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(target.getName()))
                .filter(m -> Arrays.equals(m.getParameterTypes(), target.getParameterTypes()))
                .findFirst();
    }

    private static String parameterTypeNames(Method method) {
        return Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
