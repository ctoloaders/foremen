package com.foremen.config.security;

import com.foremen.controller.AdminController;
import com.foremen.controller.AdminReadOnlyController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflection-based verification that every named CRUD {@code default} method on the generic
 * controller interfaces carries a {@link PermissionOperation} with the expected operation value.
 *
 * <p>The interfaces are generic and their endpoints are {@code default} methods, so the test
 * locates each method by name among the declared methods (bridge/synthetic copies excluded) rather
 * than by exact parameter types, then asserts the {@code @PermissionOperation} value.
 *
 * <ul>
 *   <li>{@code AdminController}: {@code create}/{@code createBulk} → CREATE (Requirement 8.1),
 *       {@code update} → UPDATE (Requirement 8.2),
 *       {@code find}/{@code findExtended}/{@code findById}/{@code getCount}/{@code getAudit}/
 *       {@code getI18nProperties}/{@code getMetadata} → READ (Requirement 8.3),
 *       {@code deleteById}/{@code setPropertiesToNull} → DELETE (Requirement 8.4).</li>
 *   <li>{@code AdminReadOnlyController}: {@code find}/{@code findById}/{@code getMetadata} → READ
 *       (Requirement 8.5).</li>
 * </ul>
 */
@DisplayName("CRUD interface @PermissionOperation mapping")
class AdminControllerOperationMappingTest {

    /**
     * Finds the (non-synthetic, non-bridge) declared method with the given name on the interface and
     * asserts it carries {@code @PermissionOperation} with {@code expectedValue}.
     */
    private void assertOperation(Class<?> iface, String methodName, String expectedValue) {
        List<Method> matches = Arrays.stream(iface.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .filter(m -> !m.isBridge() && !m.isSynthetic())
                .toList();

        assertThat(matches)
                .as("interface %s must declare exactly one method named %s",
                        iface.getSimpleName(), methodName)
                .hasSize(1);

        Method method = matches.get(0);
        PermissionOperation annotation = method.getAnnotation(PermissionOperation.class);
        assertThat(annotation)
                .as("%s.%s must be annotated with @PermissionOperation",
                        iface.getSimpleName(), methodName)
                .isNotNull();
        assertThat(annotation.value())
                .as("%s.%s @PermissionOperation value", iface.getSimpleName(), methodName)
                .isEqualTo(expectedValue);
    }

    @Nested
    @DisplayName("AdminController")
    class AdminControllerMapping {

        // --- Requirement 8.1: create / createBulk → CREATE ---

        @Test
        @DisplayName("create → CREATE")
        void createIsCreate() {
            assertOperation(AdminController.class, "create", "CREATE");
        }

        @Test
        @DisplayName("createBulk → CREATE")
        void createBulkIsCreate() {
            assertOperation(AdminController.class, "createBulk", "CREATE");
        }

        // --- Requirement 8.2: update → UPDATE ---

        @Test
        @DisplayName("update → UPDATE")
        void updateIsUpdate() {
            assertOperation(AdminController.class, "update", "UPDATE");
        }

        // --- Requirement 8.3: read endpoints → READ ---

        @Test
        @DisplayName("find → READ")
        void findIsRead() {
            assertOperation(AdminController.class, "find", "READ");
        }

        @Test
        @DisplayName("findExtended → READ")
        void findExtendedIsRead() {
            assertOperation(AdminController.class, "findExtended", "READ");
        }

        @Test
        @DisplayName("findById → READ")
        void findByIdIsRead() {
            assertOperation(AdminController.class, "findById", "READ");
        }

        @Test
        @DisplayName("getCount → READ")
        void getCountIsRead() {
            assertOperation(AdminController.class, "getCount", "READ");
        }

        @Test
        @DisplayName("getAudit → READ")
        void getAuditIsRead() {
            assertOperation(AdminController.class, "getAudit", "READ");
        }

        @Test
        @DisplayName("getI18nProperties → READ")
        void getI18nPropertiesIsRead() {
            assertOperation(AdminController.class, "getI18nProperties", "READ");
        }

        @Test
        @DisplayName("getMetadata → READ")
        void getMetadataIsRead() {
            assertOperation(AdminController.class, "getMetadata", "READ");
        }

        // --- Requirement 8.4: deleteById / setPropertiesToNull → DELETE ---

        @Test
        @DisplayName("deleteById → DELETE")
        void deleteByIdIsDelete() {
            assertOperation(AdminController.class, "deleteById", "DELETE");
        }

        @Test
        @DisplayName("setPropertiesToNull → DELETE")
        void setPropertiesToNullIsDelete() {
            assertOperation(AdminController.class, "setPropertiesToNull", "DELETE");
        }
    }

    @Nested
    @DisplayName("AdminReadOnlyController")
    class AdminReadOnlyControllerMapping {

        // --- Requirement 8.5: find / findById / getMetadata → READ ---

        @Test
        @DisplayName("find → READ")
        void findIsRead() {
            assertOperation(AdminReadOnlyController.class, "find", "READ");
        }

        @Test
        @DisplayName("findById → READ")
        void findByIdIsRead() {
            assertOperation(AdminReadOnlyController.class, "findById", "READ");
        }

        @Test
        @DisplayName("getMetadata → READ")
        void getMetadataIsRead() {
            assertOperation(AdminReadOnlyController.class, "getMetadata", "READ");
        }
    }
}
