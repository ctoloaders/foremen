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
 * <p><strong>FOR-03-04 exception:</strong> FOR-03-04 (project ownership) intentionally ships
 * {@code com.foremen.controller.ProjectMemberController} as the first legitimately-protected
 * production controller — its endpoints are guarded by {@code @RequiresPermission} on the
 * {@code PROJECT_MEMBERS} resource, in scope for that spec (FOR-03-04 Requirement 10). This is the
 * documented exception to the FOR-03-08 deferral: only ProjectMemberController is whitelisted here,
 * and every OTHER production controller must still remain free of {@code @RequiresPermission} until
 * the wholesale FOR-03-08 migration. Do NOT add further entries to the whitelist as part of
 * migrating other controllers — that belongs to FOR-03-08.
 *
 * <p>Requirements: 11.4 (FOR-03-03), 10 (FOR-03-04)
 */
@DisplayName("RequiresPermission scope guard - no production controller is annotated (FOR-03-08 deferral)")
class RequiresPermissionScopeGuardTest {

    /** Production controller package that must remain free of {@code @RequiresPermission}. */
    private static final String PRODUCTION_CONTROLLER_PACKAGE = "com.foremen.controller";

    /**
     * Controllers explicitly permitted to carry {@code @RequiresPermission} ahead of the wholesale
     * FOR-03-08 migration. {@code ProjectMemberController} is the first legitimately-protected
     * production controller, delivered by FOR-03-04 (Requirement 10). Every OTHER production
     * controller must still remain unannotated; do NOT extend this set as part of migrating other
     * controllers (that is FOR-03-08's job).
     */
    private static final Set<String> WHITELISTED_CONTROLLERS =
            Set.of("com.foremen.controller.ProjectMemberController");

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
