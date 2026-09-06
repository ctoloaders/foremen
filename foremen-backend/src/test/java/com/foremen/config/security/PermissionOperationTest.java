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
 * Reflection-based structural verification for {@link PermissionOperation}.
 *
 * <p>Validates the annotation's contract without loading the Spring context:
 * <ul>
 *   <li>Exposes a single {@code value()} attribute returning {@link String}
 *       (Requirement 2.2).</li>
 *   <li>Is retained at runtime and applicable to methods only (Requirement 2.1).</li>
 * </ul>
 */
@DisplayName("PermissionOperation annotation structure")
class PermissionOperationTest {

    // --- Requirement 2.2: single String value() attribute ---

    @Test
    @DisplayName("declares a value() attribute returning String")
    void declaresValueAttributeReturningString() throws NoSuchMethodException {
        Method value = PermissionOperation.class.getDeclaredMethod("value");
        assertThat(value.getReturnType()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("declares exactly the value() attribute")
    void declaresExactlyValueAttribute() {
        assertThat(PermissionOperation.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactly("value");
    }

    // --- Requirement 2.1: retained at runtime ---

    @Test
    @DisplayName("is retained at runtime")
    void isRetainedAtRuntime() {
        Retention retention = PermissionOperation.class.getAnnotation(Retention.class);
        assertThat(retention).as("@Retention must be present").isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    // --- Requirement 2.1: applicable to methods only ---

    @Test
    @DisplayName("targets methods only")
    void targetsMethodsOnly() {
        Target target = PermissionOperation.class.getAnnotation(Target.class);
        assertThat(target).as("@Target must be present").isNotNull();
        assertThat(target.value()).containsExactly(ElementType.METHOD);
    }
}
