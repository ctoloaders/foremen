package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.config.security.PermissionAnnotationValidator;
import com.foremen.controller.AddressController;
import com.foremen.controller.ProjectController;
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
 * Clean-startup annotation-coverage smoke test (FOR-04-13, Requirement 6.2).
 *
 * <p>{@link PermissionAnnotationValidator} is a
 * {@link org.springframework.beans.factory.SmartInitializingSingleton} that runs once after all
 * singletons are instantiated and aborts application startup (throwing {@link IllegalStateException})
 * if <em>any</em> registered controller handler is half-annotated — a {@code @PermissionResource}
 * whose in-scope handler lacks a matching {@code @PermissionOperation}/{@code @RequiresPermission},
 * or the reverse.
 *
 * <p>Booting the full application context therefore <em>is</em> the assertion: if
 * {@link ProjectController}'s ABAC annotations were incomplete — or if the intentionally
 * ABAC-unannotated {@link AddressController} tripped the validator — the context would fail to start
 * and every test method here would fail. The context loading successfully proves both controllers
 * satisfy the validator on a real (not fixture) startup.
 *
 * <p>Concretely this verifies:
 * <ul>
 *   <li>{@link ProjectController} is fully annotated: class-level {@code @PermissionResource("PROJECTS")}
 *       + inherited {@code @PermissionOperation} on the generic CRUD handlers +
 *       {@code @RequiresPermission(PROJECTS, CREATE)} on the custom create — so it classifies as
 *       COMPLETE for every mapped handler;</li>
 *   <li>{@link AddressController} carries none of the three ABAC annotations (authenticated-any-user,
 *       intentionally unguarded) and so is skipped by the validator rather than flagged as
 *       half-annotated.</li>
 * </ul>
 *
 * <p>The additional bean-registration assertions make the intent explicit: both concrete controllers
 * are registered Spring beans that the {@link PermissionAnnotationValidator} (also autowired to prove
 * it is present in the context) considered during {@code afterSingletonsInstantiated()}.
 *
 * <p>Container/profile setup mirrors {@link AddressProxyIT}: a Testcontainers PostgreSQL instance
 * under {@code @ActiveProfiles("integration-test")} with Hibernate {@code create-drop} building the
 * schema. The test performs no database writes, so it is trivially re-runnable.
 *
 * <p>Validates: Requirements 6.2
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class ProjectAnnotationCoverageSmokeIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast the UserEntity JSON converter's
            // text value into the jsonb display_preferences column, matching the deployed app.
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
     * If the context reached this point, {@link PermissionAnnotationValidator#afterSingletonsInstantiated()}
     * already ran without throwing — i.e. no controller (including {@link ProjectController} and
     * {@link AddressController}) is half-annotated. This method makes that success explicit and pins
     * the exact controllers the task cares about.
     */
    @Test
    @DisplayName("context starts cleanly — ProjectController fully annotated and AddressController intentionally unguarded both satisfy PermissionAnnotationValidator (Req 6.2)")
    void contextLoads_bothControllersSatisfyValidator() {
        // The validator itself is present and was instantiated as a singleton (it runs on startup).
        assertThat(applicationContext.getBeansOfType(PermissionAnnotationValidator.class))
                .as("the startup annotation validator must be registered and have run without aborting startup")
                .hasSize(1);

        // The fully-annotated PROJECTS controller is a registered bean the validator considered.
        assertThat(applicationContext.getBeansOfType(ProjectController.class))
                .as("ProjectController (@PermissionResource(\"PROJECTS\") + inherited @PermissionOperation "
                        + "+ @RequiresPermission on create) must be a registered, validator-considered bean")
                .hasSize(1);

        // The intentionally ABAC-unannotated address proxy is also a registered bean — it must NOT
        // have tripped the validator (which would have aborted startup before we got here).
        assertThat(applicationContext.getBeansOfType(AddressController.class))
                .as("AddressController (no ABAC annotations, authenticated-any-user) must be a registered "
                        + "bean that the validator skipped rather than flagged as half-annotated")
                .hasSize(1);
    }
}
