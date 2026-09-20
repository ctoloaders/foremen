package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.config.security.PermissionAnnotationValidator;
import com.foremen.controller.FinishingMaterialController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Clean-startup annotation-coverage smoke test (FOR-04-18, Requirements 8.10, 5.5).
 *
 * <p>{@link PermissionAnnotationValidator} is a
 * {@link org.springframework.beans.factory.SmartInitializingSingleton} that runs once after all
 * singletons are instantiated and aborts application startup (throwing {@link IllegalStateException})
 * if <em>any</em> registered controller handler is half-annotated — a {@code @PermissionResource}
 * whose in-scope handler lacks a matching {@code @PermissionOperation}/{@code @RequiresPermission},
 * or the reverse.
 *
 * <p>Booting the full application context therefore <em>is</em> the assertion: if the new
 * FOR-04-18 {@link FinishingMaterialController} had incomplete ABAC annotations, the context would
 * fail to start and every test method here would fail. The context loading successfully proves that
 * the controller satisfies the validator on a real (not fixture) startup.
 *
 * <p>Concretely this verifies:
 * <ul>
 *   <li>{@link FinishingMaterialController} — class-level
 *       {@code @PermissionResource("MATERIALS_FINISHING")} + inherited {@code @PermissionOperation}
 *       on the generic {@link com.foremen.controller.AdminController} CRUD handlers (create/list/read/
 *       update/delete/count/metadata/i18n; no custom endpoints), so it classifies as COMPLETE for
 *       every mapped handler.</li>
 * </ul>
 *
 * <p>The additional bean-registration assertion makes the intent explicit: {@link
 * FinishingMaterialController} is a registered Spring bean that the {@link
 * PermissionAnnotationValidator} (also autowired to prove it is present in the context) considered
 * during {@code afterSingletonsInstantiated()}.
 *
 * <p>Container/profile setup mirrors {@link ConstructionMaterialsAnnotationCoverageSmokeIT} /
 * {@link RoomAnnotationCoverageSmokeIT}: a Testcontainers PostgreSQL instance under
 * {@code @ActiveProfiles("integration-test")} with Hibernate {@code create-drop} building the schema.
 * The test performs no database writes, so it is trivially re-runnable.
 *
 * <p>Validates: Requirements 8.10, 5.5
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class FinishingMaterialsAnnotationCoverageSmokeIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast text values into jsonb columns,
            // matching the deployed app (e.g. the UserEntity display_preferences converter).
            .withUrlParam("stringtype", "unspecified");

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

    /**
     * If the context reached this point,
     * {@link PermissionAnnotationValidator#afterSingletonsInstantiated()} already ran without
     * throwing — i.e. the FOR-04-18 {@link FinishingMaterialController} is not half-annotated. This
     * method makes that success explicit and pins the exact controller the task cares about.
     */
    @Test
    @DisplayName("context starts cleanly — FinishingMaterialController fully annotated (@PermissionResource(\"MATERIALS_FINISHING\") + inherited @PermissionOperation on the generic CRUD handlers) satisfies PermissionAnnotationValidator (Req 8.10, 5.5)")
    void contextLoads_finishingMaterialControllerSatisfiesValidator() {
        // The validator itself is present and was instantiated as a singleton (it runs on startup).
        assertThat(applicationContext.getBeansOfType(PermissionAnnotationValidator.class))
                .as("the startup annotation validator must be registered and have run without aborting startup")
                .hasSize(1);

        // The fully-annotated MATERIALS_FINISHING operational controller: class-level
        // @PermissionResource("MATERIALS_FINISHING") + inherited @PermissionOperation on the generic
        // AdminController CRUD handlers, with no custom endpoints. If it were half-annotated, startup
        // would have aborted before we got here.
        assertThat(applicationContext.getBeansOfType(FinishingMaterialController.class))
                .as("FinishingMaterialController (@PermissionResource(\"MATERIALS_FINISHING\") + inherited "
                        + "@PermissionOperation on the generic CRUD handlers) must be a registered, "
                        + "validator-considered bean")
                .hasSize(1);
    }
}
