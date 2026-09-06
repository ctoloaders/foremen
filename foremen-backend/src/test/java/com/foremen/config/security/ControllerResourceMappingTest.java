package com.foremen.config.security;

import com.foremen.controller.AuditController;
import com.foremen.controller.AuthController;
import com.foremen.controller.DisplayPreferencesController;
import com.foremen.controller.OperationController;
import com.foremen.controller.ProjectMemberController;
import com.foremen.controller.ResourceController;
import com.foremen.controller.RoleController;
import com.foremen.controller.UserController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflection-based verification that each concrete admin controller carries (or intentionally does
 * not carry) the class-level {@link PermissionResource} annotation with the expected resource code.
 *
 * <p>Guarded controllers expose their inherited CRUD endpoints via a class-level
 * {@code @PermissionResource}, whose value the {@code PermissionResolver} combines with each
 * method's {@code @PermissionOperation} to produce the required {@code (resource, operation)} pair:
 * <ul>
 *   <li>{@code UserController} → USERS (Requirement 9.1)</li>
 *   <li>{@code RoleController} → ROLES (Requirement 9.2)</li>
 *   <li>{@code AuditController} → AUDIT (Requirement 9.3)</li>
 *   <li>{@code ResourceController} → RESOURCES (Requirement 9.4)</li>
 *   <li>{@code OperationController} → OPERATIONS (Requirement 9.5)</li>
 * </ul>
 *
 * <p>The three unguarded-by-class controllers must carry no {@code @PermissionResource}:
 * {@code ProjectMemberController} keeps its per-method {@code @RequiresPermission} on
 * {@code PROJECT_MEMBERS} (Requirement 9.6), while {@code DisplayPreferencesController}
 * (Requirement 9.7) and {@code AuthController} (Requirement 9.8) remain Unguarded, preserving their
 * self-check and public/self-service behavior.
 */
@DisplayName("Concrete controller @PermissionResource mapping")
class ControllerResourceMappingTest {

    /**
     * Asserts {@code controller} carries {@code @PermissionResource} with {@code expectedValue}.
     */
    private void assertResource(Class<?> controller, String expectedValue) {
        PermissionResource annotation = controller.getAnnotation(PermissionResource.class);
        assertThat(annotation)
                .as("%s must be annotated with @PermissionResource", controller.getSimpleName())
                .isNotNull();
        assertThat(annotation.value())
                .as("%s @PermissionResource value", controller.getSimpleName())
                .isEqualTo(expectedValue);
    }

    /**
     * Asserts {@code controller} carries no {@code @PermissionResource} annotation.
     */
    private void assertNoResource(Class<?> controller) {
        assertThat(controller.getAnnotation(PermissionResource.class))
                .as("%s must NOT be annotated with @PermissionResource", controller.getSimpleName())
                .isNull();
    }

    @Nested
    @DisplayName("guarded controllers carry the expected resource")
    class GuardedControllers {

        // --- Requirement 9.1 ---
        @Test
        @DisplayName("UserController → USERS")
        void userControllerIsUsers() {
            assertResource(UserController.class, "USERS");
        }

        // --- Requirement 9.2 ---
        @Test
        @DisplayName("RoleController → ROLES")
        void roleControllerIsRoles() {
            assertResource(RoleController.class, "ROLES");
        }

        // --- Requirement 9.3 ---
        @Test
        @DisplayName("AuditController → AUDIT")
        void auditControllerIsAudit() {
            assertResource(AuditController.class, "AUDIT");
        }

        // --- Requirement 9.4 ---
        @Test
        @DisplayName("ResourceController → RESOURCES")
        void resourceControllerIsResources() {
            assertResource(ResourceController.class, "RESOURCES");
        }

        // --- Requirement 9.5 ---
        @Test
        @DisplayName("OperationController → OPERATIONS")
        void operationControllerIsOperations() {
            assertResource(OperationController.class, "OPERATIONS");
        }
    }

    @Nested
    @DisplayName("unguarded controllers carry no @PermissionResource")
    class UnguardedControllers {

        // --- Requirement 9.6 ---
        @Test
        @DisplayName("ProjectMemberController has no @PermissionResource")
        void projectMemberControllerHasNoResource() {
            assertNoResource(ProjectMemberController.class);
        }

        // --- Requirement 9.7 ---
        @Test
        @DisplayName("DisplayPreferencesController has no @PermissionResource")
        void displayPreferencesControllerHasNoResource() {
            assertNoResource(DisplayPreferencesController.class);
        }

        // --- Requirement 9.8 ---
        @Test
        @DisplayName("AuthController has no @PermissionResource")
        void authControllerHasNoResource() {
            assertNoResource(AuthController.class);
        }
    }
}
