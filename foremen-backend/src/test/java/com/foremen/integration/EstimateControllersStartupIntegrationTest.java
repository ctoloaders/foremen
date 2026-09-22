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

import com.foremen.controller.EstimateController;
import com.foremen.controller.EstimateLineController;
import com.foremen.controller.EstimateLineRoomQtyController;
import com.foremen.testsupport.MockMvcSecurityConfig;

/**
 * Startup/wiring integration test for the three FOR-05-03 estimate controllers
 * ({@link EstimateController}, {@link EstimateLineController}, {@link EstimateLineRoomQtyController}),
 * all annotated class-level with {@code @PermissionResource("ESTIMATE")} (Requirement 9.3).
 *
 * <p>Mirrors the repo's established {@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc} +
 * {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")} harness (see
 * {@code ReferenceOptionsEndpointIntegrationTest}, {@code InviteEndToEndIntegrationTest}). Under the
 * {@code integration-test} profile Liquibase is disabled and Hibernate {@code create-drop} builds the
 * schema against a Testcontainers PostgreSQL instance.
 *
 * <p>The full application context registers every {@code @RestController} in the app, not just the
 * three estimate ones. Simply booting {@code @SpringBootTest} successfully is therefore already strong
 * evidence that {@code PermissionAnnotationValidator} (a {@code SmartInitializingSingleton}) did not
 * fail application startup due to a half-annotated controller/handler — including these estimate
 * controllers, none of which override an inherited CRUD {@code default} method without a matching
 * {@code @PermissionOperation}, and whose bespoke non-CRUD handler
 * ({@code EstimateController#getOrCreateForProject}) carries an explicit method-level
 * {@code @RequiresPermission}.
 *
 * <p>The FOR-05-03 {@code EstimateLinePackagePriceController} (and its
 * {@code /api/estimate-line-package-prices} routes, including {@code /{id}/history}) has been
 * retired (FOR-05-04, Requirements 8.2, 8.6) and is intentionally no longer asserted here.
 *
 * <p>Beyond the bare context-loads assertion, this test:
 * <ol>
 *   <li>asserts each of the three estimate controller beans is present in the context, so the test
 *       specifically documents that these controllers (not just some other controller) were
 *       exercised;</li>
 *   <li>issues an authenticated ({@code ROLE_ADMIN}) {@code GET} list request against each of the
 *       three {@code /api/estimate*} routes via {@link MockMvc} and asserts a non-5xx status,
 *       confirming the routes are live and the ABAC interceptor resolves correctly for ADMIN.</li>
 * </ol>
 *
 * <p>Validates: Requirement 9.3
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
class EstimateControllersStartupIntegrationTest {

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
    @DisplayName("Application context loads with all three ESTIMATE controllers registered as beans")
    void contextLoads_withAllThreeEstimateControllerBeans() {
        // The context loading at all (via @SpringBootTest) already proves
        // PermissionAnnotationValidator found no half-annotated controller across the whole app,
        // including these three. Resolving each bean explicitly documents that these specific
        // controllers were the ones exercised, not just "some" controller.
        assertThat(applicationContext.getBean(EstimateController.class)).isNotNull();
        assertThat(applicationContext.getBean(EstimateLineController.class)).isNotNull();
        assertThat(applicationContext.getBean(EstimateLineRoomQtyController.class)).isNotNull();
    }

    @Test
    @DisplayName("GET /api/estimates as ADMIN resolves through ABAC without a 5xx")
    @WithMockUser(roles = "ADMIN")
    void listEstimates_asAdmin_isNotServerError() throws Exception {
        mockMvc.perform(get("/api/estimates"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isLessThan(500));
    }

    @Test
    @DisplayName("GET /api/estimate-lines as ADMIN resolves through ABAC without a 5xx")
    @WithMockUser(roles = "ADMIN")
    void listEstimateLines_asAdmin_isNotServerError() throws Exception {
        mockMvc.perform(get("/api/estimate-lines"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isLessThan(500));
    }

    @Test
    @DisplayName("GET /api/estimate-line-room-qty as ADMIN resolves through ABAC without a 5xx")
    @WithMockUser(roles = "ADMIN")
    void listEstimateLineRoomQty_asAdmin_isNotServerError() throws Exception {
        mockMvc.perform(get("/api/estimate-line-room-qty"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isLessThan(500));
    }

}
