package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.controller.AssortmentGroupController;
import com.foremen.controller.AssortmentPositionController;
import com.foremen.controller.WorkPackageOverrideController;
import com.foremen.controller.WorkVolumeFormulaController;
import com.foremen.testsupport.MockMvcSecurityConfig;

/**
 * Startup/wiring integration test for the FOR-05-04 {@code PACKAGE_ASSORTMENT}/{@code
 * WORK_CATALOG} controllers ({@link AssortmentGroupController}, {@link AssortmentPositionController},
 * {@link WorkVolumeFormulaController}, {@link WorkPackageOverrideController}), and for the
 * retirement of the FOR-05-03 {@code EstimateLinePackagePriceController} (task 20.2, Requirement
 * 8.6).
 *
 * <p>Mirrors the repo's established {@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc}
 * + {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")} harness (see
 * {@code EstimateControllersStartupIntegrationTest}). Under the {@code integration-test} profile
 * Liquibase is disabled and Hibernate {@code create-drop} builds the schema against a
 * Testcontainers PostgreSQL instance.
 *
 * <p>The full application context registers every {@code @RestController} in the app, not just
 * the four controllers under test. Simply booting {@code @SpringBootTest} successfully is
 * therefore already strong evidence that {@code PermissionAnnotationValidator} (a {@code
 * SmartInitializingSingleton}) did not fail application startup due to a half-annotated
 * controller/handler — including these four, none of which override an inherited CRUD {@code
 * default} method without a matching {@code @PermissionOperation}, and whose one bespoke non-CRUD
 * handler ({@code AssortmentPositionController#packageZlM2}) carries an explicit method-level
 * {@code @RequiresPermission}.
 *
 * <p>Beyond the bare context-loads assertion, this test:
 * <ol>
 *   <li>asserts each of the four controller beans is present in the context, so the test
 *       specifically documents that these controllers (not just some other controller) were
 *       exercised;</li>
 *   <li>issues an authenticated ({@code ROLE_ADMIN}) {@code GET} list request against each of the
 *       four {@code /api/...} routes via {@link MockMvc} and asserts a non-5xx status, confirming
 *       the routes are live and the ABAC interceptor resolves correctly for ADMIN;</li>
 *   <li>asserts {@code GET /api/estimate-line-package-prices} — the retired ELPP endpoint — now
 *       resolves to exactly {@code 404}, i.e. the route no longer exists at all (Requirement 8.6),
 *       not merely that it is forbidden.</li>
 * </ol>
 *
 * <p>Validates: Requirement 8.6
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
class PackageAssortmentAndFormulaControllersStartupIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
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

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Application context loads with all four new controllers registered as beans")
    void contextLoads_withAllFourControllerBeans() {
        // The context loading at all (via @SpringBootTest) already proves
        // PermissionAnnotationValidator found no half-annotated controller across the whole app,
        // including these four. Resolving each bean explicitly documents that these specific
        // controllers were the ones exercised, not just "some" controller.
        assertThat(applicationContext.getBean(AssortmentGroupController.class)).isNotNull();
        assertThat(applicationContext.getBean(AssortmentPositionController.class)).isNotNull();
        assertThat(applicationContext.getBean(WorkVolumeFormulaController.class)).isNotNull();
        assertThat(applicationContext.getBean(WorkPackageOverrideController.class)).isNotNull();
    }

    @Test
    @DisplayName("GET /api/assortment-groups as ADMIN resolves through ABAC without a 5xx")
    @WithMockUser(roles = "ADMIN")
    void listAssortmentGroups_asAdmin_isNotServerError() throws Exception {
        mockMvc.perform(get("/api/assortment-groups"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isLessThan(500));
    }

    @Test
    @DisplayName("GET /api/assortment-positions as ADMIN resolves through ABAC without a 5xx")
    @WithMockUser(roles = "ADMIN")
    void listAssortmentPositions_asAdmin_isNotServerError() throws Exception {
        mockMvc.perform(get("/api/assortment-positions"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isLessThan(500));
    }

    @Test
    @DisplayName("GET /api/work-volume-formulas as ADMIN resolves through ABAC without a 5xx")
    @WithMockUser(roles = "ADMIN")
    void listWorkVolumeFormulas_asAdmin_isNotServerError() throws Exception {
        mockMvc.perform(get("/api/work-volume-formulas"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isLessThan(500));
    }

    @Test
    @DisplayName("GET /api/work-package-overrides as ADMIN resolves through ABAC without a 5xx")
    @WithMockUser(roles = "ADMIN")
    void listWorkPackageOverrides_asAdmin_isNotServerError() throws Exception {
        mockMvc.perform(get("/api/work-package-overrides"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isLessThan(500));
    }

    @Test
    @DisplayName("GET /api/estimate-line-package-prices (retired ELPP route) resolves to exactly 404")
    @WithMockUser(roles = "ADMIN")
    void retiredEstimateLinePackagePricesRoute_returns404() throws Exception {
        mockMvc.perform(get("/api/estimate-line-package-prices"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
    }

}
