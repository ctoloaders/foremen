package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.WorkMaterialConsumptionService;
import com.foremen.testsupport.MockMvcSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup / context smoke test for the work-material-consumption vertical (FOR-04-19, task 12.3).
 *
 * <p>This test boots the full Spring application context against a Testcontainers PostgreSQL — the
 * same {@code @SpringBootTest} + Testcontainers boot pattern as the FOR-04-17/18 sibling
 * integration tests. The point is not to exercise an endpoint but to prove three startup-time
 * guarantees hold for the fully annotated {@link WorkMaterialConsumptionController}:
 *
 * <ol>
 *   <li><b>The context loads (Requirement 6.5).</b> {@code PermissionAnnotationValidator} is a
 *       {@link org.springframework.beans.factory.SmartInitializingSingleton} that runs once after
 *       all singletons are instantiated and throws — failing the context — if any controller is
 *       half-annotated (a {@code @PermissionResource} without a matching {@code @PermissionOperation}
 *       on an in-scope handler, or vice versa). Because the controller inherits its CRUD handlers
 *       fully annotated from {@code AdminController} and carries the class-level
 *       {@code @PermissionResource}, a context that loads without error is proof the validator
 *       passed it.</li>
 *   <li><b>The controller bean is present and carries {@code @PermissionResource("WORK_MATERIAL_CONSUMPTION")}</b>
 *       — matching the resource {@code code} seeded by changeset 068 (Requirement 6.5).</li>
 *   <li><b>The service is NOT project-scoped (Requirement 2.8).</b> A guard assertion confirms
 *       {@link WorkMaterialConsumptionService} does not implement {@link ProjectScopedService}, so
 *       no {@code getProjectIdPath()} is required (per {@code .kiro/steering/entity-creation-rules.md}
 *       step 4 — {@code WORK_MATERIAL_CONSUMPTION} is a GLOBAL admin resource).</li>
 * </ol>
 *
 * <p>Feature: FOR-04-19-work-catalog-material-consumption
 *
 * <p><b>Validates: Requirements 2.8, 6.5, 10.8</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@Tag("Feature: FOR-04-19-work-catalog-material-consumption, task 12.3: startup smoke + not-project-scoped guard")
class WorkMaterialConsumptionStartupSmokeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WorkMaterialConsumptionController workMaterialConsumptionController;

    @Test
    @DisplayName("context loads (PermissionAnnotationValidator passed) and the fully annotated "
            + "WorkMaterialConsumptionController bean is present — Req 6.5")
    void contextLoadsWithFullyAnnotatedController() {
        // Reaching this point means the SmartInitializingSingleton PermissionAnnotationValidator
        // ran during startup without throwing: the controller is NOT half-annotated (Req 6.5).
        assertThat(applicationContext.getBeanNamesForType(WorkMaterialConsumptionController.class))
                .as("the WorkMaterialConsumptionController bean must be registered in the context")
                .isNotEmpty();
        assertThat(workMaterialConsumptionController)
                .as("the controller bean is injectable, so the context started cleanly")
                .isNotNull();
    }

    @Test
    @DisplayName("WorkMaterialConsumptionController carries @PermissionResource(\"WORK_MATERIAL_CONSUMPTION\") — Req 6.5")
    void controllerCarriesPermissionResourceAnnotation() {
        Class<?> targetClass = AopUtils.getTargetClass(workMaterialConsumptionController);
        PermissionResource permissionResource =
                AnnotatedElementUtils.findMergedAnnotation(targetClass, PermissionResource.class);

        assertThat(permissionResource)
                .as("the concrete controller must be annotated with @PermissionResource")
                .isNotNull();
        assertThat(permissionResource.value())
                .as("the resource code must match the seeded WORK_MATERIAL_CONSUMPTION resource (changeset 068)")
                .isEqualTo("WORK_MATERIAL_CONSUMPTION");
    }

    @Test
    @DisplayName("WorkMaterialConsumptionService does NOT implement ProjectScopedService "
            + "(global admin resource) — Req 2.8")
    void serviceIsNotProjectScoped() {
        assertThat(ProjectScopedService.class.isAssignableFrom(WorkMaterialConsumptionService.class))
                .as("WORK_MATERIAL_CONSUMPTION is a GLOBAL admin resource; its service must NOT be "
                        + "project-scoped (no getProjectIdPath), per entity-creation-rules.md step 4")
                .isFalse();
    }
}
