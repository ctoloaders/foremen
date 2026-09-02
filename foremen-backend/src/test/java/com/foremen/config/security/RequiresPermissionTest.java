package com.foremen.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflection-based structural verification for {@link RequiresPermission}.
 *
 * <p>Validates the annotation's contract without loading the Spring context:
 * <ul>
 *   <li>{@code resource()} and {@code operation()} attributes return {@link String}
 *       (Requirement 4.1).</li>
 *   <li>The annotation is retained at runtime and applicable to methods only
 *       (Requirement 4.2).</li>
 * </ul>
 */
@DisplayName("RequiresPermission annotation structure")
class RequiresPermissionTest {

    // --- Requirement 4.1: resource() and operation() attributes are String-typed ---

    @Test
    @DisplayName("declares a resource() attribute returning String")
    void declaresResourceAttributeReturningString() throws NoSuchMethodException {
        Method resource = RequiresPermission.class.getDeclaredMethod("resource");
        assertThat(resource.getReturnType()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("declares an operation() attribute returning String")
    void declaresOperationAttributeReturningString() throws NoSuchMethodException {
        Method operation = RequiresPermission.class.getDeclaredMethod("operation");
        assertThat(operation.getReturnType()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("declares exactly the resource() and operation() attributes")
    void declaresExactlyResourceAndOperationAttributes() {
        assertThat(RequiresPermission.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("resource", "operation");
    }

    // --- Requirement 4.2: retained at runtime ---

    @Test
    @DisplayName("is retained at runtime")
    void isRetainedAtRuntime() {
        Retention retention = RequiresPermission.class.getAnnotation(Retention.class);
        assertThat(retention).as("@Retention must be present").isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    // --- Requirement 4.2: applicable to methods only ---

    @Test
    @DisplayName("targets methods only")
    void targetsMethodsOnly() {
        Target target = RequiresPermission.class.getAnnotation(Target.class);
        assertThat(target).as("@Target must be present").isNotNull();
        assertThat(target.value()).containsExactly(ElementType.METHOD);
    }
}
