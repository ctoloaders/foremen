package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.config.security.PermissionAnnotationValidator;
import com.foremen.controller.ConstructionMaterialController;
import com.foremen.controller.ConstructionMaterialTypeController;
import com.foremen.controller.ImageController;
import com.foremen.controller.MaterialSellerController;
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
 * Clean-startup annotation-coverage smoke test (FOR-04-17, Requirements 9.5, 9.6, 12.10/12.11).
 *
 * <p>{@link PermissionAnnotationValidator} is a
 * {@link org.springframework.beans.factory.SmartInitializingSingleton} that runs once after all
 * singletons are instantiated and aborts application startup (throwing {@link IllegalStateException})
 * if <em>any</em> registered controller handler is half-annotated — a {@code @PermissionResource}
 * whose in-scope handler lacks a matching {@code @PermissionOperation}/{@code @RequiresPermission},
 * or the reverse.
 *
 * <p>Booting the full application context therefore <em>is</em> the assertion: if any of the new
 * FOR-04-17 controllers had incomplete ABAC annotations — or if the intentionally
 * ABAC-unannotated {@link ImageController} tripped the validator — the context would fail to start
 * and every test method here would fail. The context loading successfully proves that all of the
 * new/changed controllers satisfy the validator on a real (not fixture) startup.
 *
 * <p>Concretely this verifies:
 * <ul>
 *   <li>{@link ConstructionMaterialTypeController} — class-level
 *       {@code @PermissionResource("CONSTRUCTION_MATERIAL_TYPES")} + inherited
 *       {@code @PermissionOperation} on the generic {@link com.foremen.controller.AdminController}
 *       CRUD handlers, so it classifies as COMPLETE for every mapped handler;</li>
 *   <li>{@link MaterialSellerController} — class-level {@code @PermissionResource("MATERIAL_SELLERS")}
 *       + inherited {@code @PermissionOperation} on the generic CRUD handlers;</li>
 *   <li>{@link ConstructionMaterialController} — class-level
 *       {@code @PermissionResource("MATERIALS_CONSTRUCTION")} + inherited {@code @PermissionOperation}
 *       on the generic CRUD handlers + the custom {@code GET /price-ranges} carrying a method-level
 *       {@code @RequiresPermission("MATERIALS_CONSTRUCTION", "READ")} (which takes precedence for
 *       that handler), so every mapped handler is COMPLETE;</li>
 *   <li>{@link ImageController} — the custom {@code POST /api/images} carries none of the three ABAC
 *       annotations (dynamic-resource, programmatically enforced), so the validator classifies it as
 *       COMPLETE (none-of-three) and skips it rather than flagging it as half-annotated.</li>
 * </ul>
 *
 * <p>The additional bean-registration assertions make the intent explicit: all four concrete
 * controllers are registered Spring beans that the {@link PermissionAnnotationValidator} (also
 * autowired to prove it is present in the context) considered during
 * {@code afterSingletonsInstantiated()}.
 *
 * <p>Container/profile setup mirrors {@link RoomAnnotationCoverageSmokeIT} /
 * {@link ProjectAnnotationCoverageSmokeIT}: a Testcontainers PostgreSQL instance under
 * {@code @ActiveProfiles("integration-test")} with Hibernate {@code create-drop} building the schema.
 * The test performs no database writes, so it is trivially re-runnable.
 *
 * <p>Validates: Requirements 9.5, 9.6, 12.10
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class ConstructionMaterialsAnnotationCoverageSmokeIT {

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
     * throwing — i.e. none of the new/changed FOR-04-17 controllers is half-annotated. This method
     * makes that success explicit and pins the exact controllers the task cares about.
     */
    @Test
    @DisplayName("context starts cleanly — the FOR-04-17 controllers (CONSTRUCTION_MATERIAL_TYPES / MATERIAL_SELLERS / MATERIALS_CONSTRUCTION incl. /price-ranges, plus the intentionally unguarded POST /api/images) all satisfy PermissionAnnotationValidator (Req 9.5, 9.6, 12.10)")
    void contextLoads_allConstructionMaterialControllersSatisfyValidator() {
        // The validator itself is present and was instantiated as a singleton (it runs on startup).
        assertThat(applicationContext.getBeansOfType(PermissionAnnotationValidator.class))
                .as("the startup annotation validator must be registered and have run without aborting startup")
                .hasSize(1);

        // Track 1 — the fully-annotated CONSTRUCTION_MATERIAL_TYPES dictionary controller.
        assertThat(applicationContext.getBeansOfType(ConstructionMaterialTypeController.class))
                .as("ConstructionMaterialTypeController (@PermissionResource(\"CONSTRUCTION_MATERIAL_TYPES\") "
                        + "+ inherited @PermissionOperation on the generic CRUD handlers) must be a registered, "
                        + "validator-considered bean")
                .hasSize(1);

        // Track 2 — the fully-annotated MATERIAL_SELLERS dictionary controller.
        assertThat(applicationContext.getBeansOfType(MaterialSellerController.class))
                .as("MaterialSellerController (@PermissionResource(\"MATERIAL_SELLERS\") + inherited "
                        + "@PermissionOperation on the generic CRUD handlers) must be a registered, "
                        + "validator-considered bean")
                .hasSize(1);

        // Track 3 — the fully-annotated MATERIALS_CONSTRUCTION operational controller, including the
        // custom GET /price-ranges guarded by a method-level @RequiresPermission.
        assertThat(applicationContext.getBeansOfType(ConstructionMaterialController.class))
                .as("ConstructionMaterialController (@PermissionResource(\"MATERIALS_CONSTRUCTION\") + inherited "
                        + "@PermissionOperation on the generic CRUD handlers + @RequiresPermission on "
                        + "GET /price-ranges) must be a registered, validator-considered bean")
                .hasSize(1);

        // Shared — the intentionally ABAC-unannotated image upload endpoint. POST /api/images enforces
        // its dynamic target resource programmatically, so it carries none of the three annotations and
        // must be SKIPPED by the validator (not flagged half-annotated) — otherwise startup would have
        // aborted before we got here.
        assertThat(applicationContext.getBeansOfType(ImageController.class))
                .as("ImageController (POST /api/images, none-of-three ABAC annotations, programmatic dynamic-"
                        + "resource enforcement) must be a registered bean the validator skipped rather than "
                        + "flagged as half-annotated")
                .hasSize(1);
    }
}
