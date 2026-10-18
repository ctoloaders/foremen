package com.foremen.controller;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.foremen.config.security.PermissionResource;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.WorkReportService;
import com.foremen.testsupport.MockMvcSecurityConfig;

/**
 * FOR-06-01 Amendment A1 task A1.3 — startup / context smoke test for {@link WorkReportController}
 * after the two matrix batch-authoring endpoints ({@code GET /api/work-reports/progress} guarded
 * {@code WORK_REPORTS}/{@code READ}, {@code POST /api/work-reports/batch} guarded
 * {@code WORK_REPORTS}/{@code CREATE}) were added.
 *
 * <p>Boots the full Spring application context against a Testcontainers PostgreSQL — the same
 * {@code @SpringBootTest} + Testcontainers boot pattern as {@link OfferControllersStartupSmokeTest}.
 * The point is not to exercise an endpoint but to prove the startup-time guarantee: the
 * {@link com.foremen.config.security.PermissionAnnotationValidator} (a
 * {@link org.springframework.beans.factory.SmartInitializingSingleton} that runs once after all
 * singletons are instantiated) classified every {@code WorkReportController} handler — the inherited
 * CRUD defaults, the three review transitions, and the two new {@code @RequiresPermission}-guarded
 * matrix endpoints — as COMPLETE. A context that loads without error is proof the validator did not
 * throw, so no handler is half-annotated.
 *
 * <p>Also asserts the controller still carries {@code @PermissionResource("WORK_REPORTS")} and that
 * {@link WorkReportService} remains a {@link ProjectScopedService} (the resource is project-scoped
 * per {@code .kiro/steering/entity-creation-rules.md} step 4).
 *
 * <p>Runs with {@code ddl-auto: create-drop} and Liquibase disabled (like the sibling smoke tests),
 * so it makes no assertion about the seeded ABAC matrix rows — Amendment A1 adds no new resource or
 * grant (it reuses the existing {@code WORK_REPORTS} READ/CREATE grants seeded by changeset 165).
 *
 * <p>Feature: FOR-06-01-work-reporting
 *
 * <p>Validates: Requirements 14.6, 16.1, 16.6
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@Tag("Feature: FOR-06-01-work-reporting, task A1.3: WorkReportController batch endpoints startup smoke")
class WorkReportControllerStartupSmokeTest {

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
    private WorkReportController workReportController;

    @Test
    @DisplayName("context loads (PermissionAnnotationValidator passed) — WorkReportController with progress + batch is fully annotated")
    void contextLoadsWithFullyAnnotatedController() {
        // Reaching this point means the SmartInitializingSingleton PermissionAnnotationValidator ran
        // during startup without throwing: no WorkReportController handler is half-annotated — the two
        // new matrix endpoints carry a method-level @RequiresPermission (READ / CREATE).
        assertThat(workReportController)
                .as("WorkReportController bean must be injectable").isNotNull();
        assertThat(applicationContext.getBeanNamesForType(WorkReportController.class)).isNotEmpty();
    }

    @Test
    @DisplayName("WorkReportController carries @PermissionResource(\"WORK_REPORTS\")")
    void controllerCarriesExpectedPermissionResource() {
        Class<?> targetClass = AopUtils.getTargetClass(workReportController);
        PermissionResource permissionResource =
                AnnotatedElementUtils.findMergedAnnotation(targetClass, PermissionResource.class);
        assertThat(permissionResource)
                .as("WorkReportController must carry @PermissionResource").isNotNull();
        assertThat(permissionResource.value()).isEqualTo("WORK_REPORTS");
    }

    @Test
    @DisplayName("WorkReportService is project-scoped (WORK_REPORTS is project-scoped)")
    void serviceScoping() {
        assertThat(ProjectScopedService.class.isAssignableFrom(WorkReportService.class))
                .as("WorkReportService must be a ProjectScopedService (WORK_REPORTS is project-scoped)")
                .isTrue();
    }
}
