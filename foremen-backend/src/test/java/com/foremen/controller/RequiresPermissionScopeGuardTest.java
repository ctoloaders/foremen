package com.foremen.controller;

import com.foremen.config.security.RequiresPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scope-guard reflection test enforcing the FOR-03-08 deferral.
 *
 * <p>This spec (FOR-03-03) delivers the {@code @RequiresPermission} enforcement mechanism plus a
 * single test-only demonstrative controller ({@code com.foremen.testsupport.DemoPermissionController}),
 * but it MUST NOT migrate any existing production controller onto {@code @RequiresPermission}. That
 * migration is explicitly deferred to FOR-03-08 (Requirement 11.4).
 *
 * <p>This test scans every class in the production {@code com.foremen.controller} package via
 * classpath scanning and asserts that NO production controller carries {@code @RequiresPermission}
 * at either the type level or on any of its declared methods. The demonstrative controller lives in
 * the {@code com.foremen.testsupport} test-source package, so it is outside the scanned package and
 * is correctly not flagged.
 *
 * <p><strong>FOR-03-05 exception:</strong> {@code com.foremen.controller.UserController} is the one
 * production controller legitimately carrying {@code @RequiresPermission} ahead of the wholesale
 * FOR-03-08 migration: its {@code registerClient} endpoint (POST /api/users/client) is guarded by
 * {@code @RequiresPermission} on the {@code PROJECTS} resource (FOR-03-05 Requirement 10.2). It is
 * the sole whitelisted controller; every OTHER production controller must still remain free of
 * {@code @RequiresPermission}. Do NOT add further entries to the whitelist.
 *
 * <p><strong>FOR-05-09 note:</strong> {@code ProjectMemberController} used to be whitelisted here
 * because FOR-03-04 guarded it with per-method {@code @RequiresPermission} on {@code PROJECT_MEMBERS}.
 * FOR-05-09 task 5.2 migrated it onto a class-level {@code @PermissionResource("PROJECT_MEMBERS")}
 * plus a per-handler {@code @PermissionOperation}, so it no longer carries {@code @RequiresPermission}
 * at all and is therefore removed from the whitelist — it now passes this guard like any other
 * {@code @PermissionResource}-guarded controller. Its {@code (PROJECT_MEMBERS, op)} resolution is
 * asserted by {@code ControllerResourceMappingTest} (FOR-05-09 TC-RP-03 / REG-03).
 *
 * <p>Requirements: 11.4 (FOR-03-03), 10.2 (FOR-03-05), 2 (FOR-05-09)
 */
@DisplayName("RequiresPermission scope guard - no production controller is annotated (FOR-03-08 deferral)")
class RequiresPermissionScopeGuardTest {

    /** Production controller package that must remain free of {@code @RequiresPermission}. */
    private static final String PRODUCTION_CONTROLLER_PACKAGE = "com.foremen.controller";

    /**
     * Controllers explicitly permitted to carry {@code @RequiresPermission} ahead of the wholesale
     * FOR-03-08 migration. {@code UserController} is whitelisted for FOR-03-05 (Requirement 10.2):
     * its {@code registerClient} endpoint (POST /api/users/client) legitimately carries
     * {@code @RequiresPermission} on the {@code PROJECTS} resource. {@code ProjectMemberController}
     * is intentionally NOT whitelisted: FOR-05-09 task 5.2 moved it from {@code @RequiresPermission}
     * onto {@code @PermissionResource("PROJECT_MEMBERS")} + per-handler {@code @PermissionOperation},
     * so it carries no {@code @RequiresPermission} and must pass this guard unaided. Every OTHER
     * production controller must likewise remain unannotated; do NOT extend this set.
     */
    private static final Set<String> WHITELISTED_CONTROLLERS =
            Set.of(
                    "com.foremen.controller.UserController");

    @Test
    @DisplayName("no production controller type or method carries @RequiresPermission")
    void noProductionControllerCarriesRequiresPermission() {
        List<Class<?>> controllers = scanProductionControllerClasses();

        assertThat(controllers)
                .as("production controller package should contain at least one class to scan")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();

        for (Class<?> controller : controllers) {
            // FOR-03-04 exception: skip the single whitelisted, legitimately-protected controller.
            if (WHITELISTED_CONTROLLERS.contains(controller.getName())) {
                continue;
            }
            if (controller.isAnnotationPresent(RequiresPermission.class)) {
                violations.add(controller.getName() + " (type-level @RequiresPermission)");
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isAnnotationPresent(RequiresPermission.class)) {
                    violations.add(controller.getName() + "#" + method.getName()
                            + " (method-level @RequiresPermission)");
                }
            }
        }

        assertThat(violations)
                .as("no production controller in %s (other than the FOR-03-04 whitelist %s) may "
                        + "carry @RequiresPermission (migration of the rest is deferred to FOR-03-08 "
                        + "per Requirement 11.4)",
                        PRODUCTION_CONTROLLER_PACKAGE, WHITELISTED_CONTROLLERS)
                .isEmpty();
    }

    /**
     * Enumerates every type defined in the production controller package (recursively, including
     * sub-packages such as {@code advice}) using Spring's classpath scanner.
     *
     * <p>The scanner is configured with a match-everything include filter, and
     * {@code isCandidateComponent} is overridden to also return interfaces and abstract classes.
     * This is essential because the generic {@code AdminController} interface (and its
     * REST-mapped {@code default} methods, inherited by every concrete controller) would otherwise
     * be skipped by the default candidate rules — yet it is exactly the kind of shared type that an
     * accidental FOR-03-08 migration could annotate.
     *
     * @return the loaded classes found in {@link #PRODUCTION_CONTROLLER_PACKAGE}
     */
    private List<Class<?>> scanProductionControllerClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                        // Include interfaces and abstract classes as well as concrete classes.
                        return true;
                    }
                };
        // Match every type in the package (and sub-packages) regardless of stereotype annotations.
        TypeFilter matchAll = new TypeFilter() {
            @Override
            public boolean match(MetadataReader metadataReader, MetadataReaderFactory factory) {
                return true;
            }
        };
        scanner.addIncludeFilter(matchAll);

        Set<BeanDefinition> candidates = scanner.findCandidateComponents(PRODUCTION_CONTROLLER_PACKAGE);

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : candidates) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                classes.add(Class.forName(className, false, getClass().getClassLoader()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Failed to load scanned controller class: " + className, e);
            }
        }
        return classes;
    }
}
