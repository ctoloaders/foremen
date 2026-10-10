package com.foremen.config.security;

import com.foremen.controller.AuditController;
import com.foremen.controller.AuthController;
import com.foremen.controller.DisplayPreferencesController;
import com.foremen.controller.OperationController;
import com.foremen.controller.ProjectMemberController;
import com.foremen.controller.ProjectScheduleController;
import com.foremen.controller.ResourceController;
import com.foremen.controller.RoleController;
import com.foremen.controller.UserController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
 *   <li>{@code ProjectScheduleController} → WORK_SCHEDULE (FOR-05-10 Requirement 18.3)</li>
 * </ul>
 *
 * <p>{@code DisplayPreferencesController} (FOR-03-08 Requirement 9.7) and {@code AuthController}
 * (Requirement 9.8) remain Unguarded by class and must carry no {@code @PermissionResource},
 * preserving their self-check and public / self-service behavior.
 *
 * <p><b>FOR-05-09 update (TC-RP-03 / REG-03).</b> {@code ProjectMemberController} is no longer
 * Unguarded-by-class: FOR-05-09 task 5.2 migrated it from per-method {@code @RequiresPermission} onto
 * a class-level {@code @PermissionResource("PROJECT_MEMBERS")} combined with a per-handler
 * {@code @PermissionOperation}, so that the new UPDATE Attribute_Update / deactivate / reactivate
 * handlers resolve to a {@code (PROJECT_MEMBERS, op)} pair alongside the existing CREATE / READ /
 * DELETE handlers (FOR-05-09 Requirement 2 criteria 1, 2). This test therefore asserts that the
 * controller carries {@code @PermissionResource("PROJECT_MEMBERS")}, that <em>every</em> HTTP
 * handler resolves (via the real {@link PermissionResolver}) to a pair whose resource is
 * {@code PROJECT_MEMBERS} with the operation mandated by Requirement 2 criterion 1, that no handler
 * is left Unguarded, and that no {@code PROJECT_TEAM} resource is referenced anywhere on the
 * controller or its handlers (decision D1, FOR-05-09 Requirement 2 criterion 2).
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

        // --- FOR-05-10 Requirement 18.3: the planning-Gantt controller joins the guarded set ---
        @Test
        @DisplayName("ProjectScheduleController → WORK_SCHEDULE")
        void projectScheduleControllerIsWorkSchedule() {
            assertResource(ProjectScheduleController.class, "WORK_SCHEDULE");
        }
    }

    @Nested
    @DisplayName("unguarded controllers carry no @PermissionResource")
    class UnguardedControllers {

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

    /**
     * FOR-05-09 TC-RP-03 / REG-03: {@code ProjectMemberController} resolves to {@code PROJECT_MEMBERS}
     * on <em>every</em> handler, including the new UPDATE handler (the PATCH Attribute_Update that
     * dispatches to Worker_Type change, tag change, and the deactivate / reactivate Assignment_Status
     * change), with no {@code PROJECT_TEAM} reference anywhere (decision D1, Requirement 2 criteria 1,
     * 2). Resolution is checked through the production {@link PermissionResolver} so the assertion is
     * exactly what the {@code PermissionInterceptor} enforces at runtime.
     */
    @Nested
    @DisplayName("ProjectMemberController resolves to PROJECT_MEMBERS on every handler (FOR-05-09 TC-RP-03 / REG-03)")
    class ProjectMembersResolution {

        private final PermissionResolver resolver = new PermissionResolver();

        /** The HTTP-mapping annotations that mark a request handler on the controller. */
        private final Set<Class<? extends Annotation>> httpMappingAnnotations = Set.of(
                GetMapping.class, PostMapping.class, PatchMapping.class,
                PutMapping.class, DeleteMapping.class, RequestMapping.class);

        // --- Requirement 2 criterion 1 (class resource) ---
        @Test
        @DisplayName("ProjectMemberController carries @PermissionResource(\"PROJECT_MEMBERS\")")
        void controllerCarriesProjectMembersResource() {
            assertResource(ProjectMemberController.class, "PROJECT_MEMBERS");
        }

        // --- Requirement 2 criterion 1 (every handler resolves to a PROJECT_MEMBERS pair) ---
        @Test
        @DisplayName("every handler resolves to (PROJECT_MEMBERS, op) with no Unguarded handler")
        void everyHandlerResolvesToProjectMembers() {
            List<Method> handlers = httpHandlers();

            assertThat(handlers)
                    .as("ProjectMemberController must expose at least its CREATE / READ / UPDATE / "
                            + "DELETE handlers")
                    .isNotEmpty();

            for (Method handler : handlers) {
                HandlerMethod hm = new HandlerMethod(controllerInstance(), handler);
                PermissionResolver.ResolvedPair pair = resolver.resolve(hm);

                assertThat(pair)
                        .as("handler %s must resolve to a (resource, operation) pair — no Team_API "
                                + "handler may be left Unguarded (Requirement 2 criterion 1)",
                                handler.getName())
                        .isNotNull();
                assertThat(pair.resource())
                        .as("handler %s must resolve to the PROJECT_MEMBERS resource", handler.getName())
                        .isEqualTo("PROJECT_MEMBERS");
                assertThat(pair.operation())
                        .as("handler %s must resolve to one of CREATE / READ / UPDATE / DELETE",
                                handler.getName())
                        .isIn("CREATE", "READ", "UPDATE", "DELETE");
            }
        }

        // --- Requirement 2 criterion 1 (the exact operation mandated per handler) ---
        @Test
        @DisplayName("each handler resolves to the operation Requirement 2 criterion 1 mandates")
        void handlersResolveToTheMandatedOperation() {
            // READ: list members, readiness, list a user's project ids.
            assertOperation("listMembers", "READ");
            assertOperation("readiness", "READ");
            assertOperation("listProjects", "READ");
            // CREATE: assign and candidate lookup (candidate lookup exists only to add members).
            assertOperation("assign", "CREATE");
            assertOperation("listCandidates", "CREATE");
            // UPDATE: the single PATCH Attribute_Update (Worker_Type / tags / deactivate-reactivate).
            assertOperation("updateAttributes", "UPDATE");
            // DELETE: remove.
            assertOperation("remove", "DELETE");
        }

        // --- Requirement 2 criterion 2 (no PROJECT_TEAM reference anywhere) ---
        @Test
        @DisplayName("no @PermissionResource / @RequiresPermission on the controller or any handler names PROJECT_TEAM")
        void noProjectTeamReferenceAnywhere() {
            PermissionResource classResource = AnnotatedElementUtils.findMergedAnnotation(
                    ProjectMemberController.class, PermissionResource.class);
            assertThat(classResource)
                    .as("ProjectMemberController must carry a class-level @PermissionResource")
                    .isNotNull();
            assertThat(classResource.value())
                    .as("the class @PermissionResource must name PROJECT_MEMBERS, never PROJECT_TEAM (D1)")
                    .isEqualTo("PROJECT_MEMBERS");

            for (Method handler : ProjectMemberController.class.getDeclaredMethods()) {
                RequiresPermission requires = AnnotatedElementUtils.findMergedAnnotation(
                        handler, RequiresPermission.class);
                if (requires != null) {
                    assertThat(requires.resource())
                            .as("handler %s must not name PROJECT_TEAM in @RequiresPermission",
                                    handler.getName())
                            .isNotEqualTo("PROJECT_TEAM");
                }
            }
        }

        /** Asserts the named handler resolves to {@code (PROJECT_MEMBERS, expectedOperation)}. */
        private void assertOperation(String methodName, String expectedOperation) {
            Method handler = findHandler(methodName);
            HandlerMethod hm = new HandlerMethod(controllerInstance(), handler);
            PermissionResolver.ResolvedPair pair = resolver.resolve(hm);
            assertThat(pair)
                    .as("handler %s must resolve to a pair", methodName)
                    .isNotNull();
            assertThat(pair.resource())
                    .as("handler %s resource", methodName)
                    .isEqualTo("PROJECT_MEMBERS");
            assertThat(pair.operation())
                    .as("handler %s operation (Requirement 2 criterion 1)", methodName)
                    .isEqualTo(expectedOperation);
        }

        private Method findHandler(String methodName) {
            return httpHandlers().stream()
                    .filter(m -> m.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "ProjectMemberController has no HTTP handler named " + methodName));
        }

        /** Every declared method carrying an HTTP-mapping annotation. */
        private List<Method> httpHandlers() {
            return java.util.Arrays.stream(ProjectMemberController.class.getDeclaredMethods())
                    .filter(this::isHttpHandler)
                    .collect(Collectors.toList());
        }

        private boolean isHttpHandler(Method method) {
            return httpMappingAnnotations.stream()
                    .anyMatch(a -> AnnotatedElementUtils.findMergedAnnotation(method, a) != null);
        }

        /**
         * Builds a {@link ProjectMemberController} instance for the {@link HandlerMethod} bean,
         * invoking its sole declared constructor (the Lombok {@code @RequiredArgsConstructor},
         * taking the service) with {@code null} arguments. {@code PermissionResolver.resolve} only
         * reads {@code HandlerMethod.getBeanType()} and {@code getMethod()}, so the uninitialized
         * service is never dereferenced. Reflecting over the constructor avoids compiling against a
         * fixed arity.
         */
        private ProjectMemberController controllerInstance() {
            try {
                var constructor = ProjectMemberController.class.getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                Object[] args = new Object[constructor.getParameterCount()];
                return (ProjectMemberController) constructor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not instantiate ProjectMemberController", e);
            }
        }
    }
}
