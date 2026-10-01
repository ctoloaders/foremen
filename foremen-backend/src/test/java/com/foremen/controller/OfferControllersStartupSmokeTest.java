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
import com.foremen.service.OfferService;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.offer.NotificationService;
import com.foremen.testsupport.MockMvcSecurityConfig;

/**
 * FOR-05-07 task 9.4 — startup / context smoke test for the four Offer-stage controllers
 * ({@link OfferController}, {@link OfferNegotiationController}, {@link ClientOfferController},
 * {@link NotificationController}).
 *
 * <p>Boots the full Spring application context against a Testcontainers PostgreSQL — the same
 * {@code @SpringBootTest} + Testcontainers boot pattern as {@link WorkMaterialConsumptionStartupSmokeTest}
 * and the FOR-05-05/06 sibling integration tests. The point is not to exercise an endpoint but to
 * prove the startup-time guarantees task 9.4 mandates for the fully annotated Offer-stage controllers:
 *
 * <ol>
 *   <li><b>PermissionAnnotationValidator classified all four controllers COMPLETE.</b> The validator
 *       is a {@link org.springframework.beans.factory.SmartInitializingSingleton} that runs once after
 *       all singletons are instantiated and throws — failing the context — if any controller is
 *       half-annotated (a {@code @PermissionResource} without a matching {@code @RequiresPermission}/
 *       {@code @PermissionOperation} on an in-scope handler, or vice versa). Because a context that
 *       loads without error is proof the validator passed, and all four beans are asserted present and
 *       carrying the expected {@code @PermissionResource}, the whole Offer-stage controller set is
 *       classified COMPLETE (R5.11, R16.3, R13.13).</li>
 *   <li><b>The three offer controllers share {@code @PermissionResource("OFFERS")}</b> (matching the
 *       resource code seeded by changeset 133) and the bell controller carries
 *       {@code @PermissionResource("NOTIFICATIONS")} (changeset 134).</li>
 *   <li><b>{@link OfferService} is project-scoped; {@link NotificationService} is NOT.</b> Per
 *       {@code .kiro/steering/entity-creation-rules.md} step 4, the {@code OFFERS} resource is
 *       project-scoped ({@code offer.project.id}) while {@code NOTIFICATIONS} ownership is enforced by
 *       the acting user id, so its service must not be a {@link ProjectScopedService}.</li>
 * </ol>
 *
 * <p>This profile runs with {@code ddl-auto: create-drop} and Liquibase disabled (like the sibling
 * smoke tests), so it deliberately makes no assertion about the <b>seeded</b> ABAC matrix rows — that
 * seed-time verification (the tightened OFFERS/NOTIFICATIONS grants applied by 133/134/135) lives in
 * the Liquibase migration integration test (task 1.9) and in the changeset-level assertions of
 * {@link OfferControllersAbacTest}.
 *
 * <p>Feature: FOR-05-07-offer-approval
 *
 * <p>Validates: Requirements 5.11, 16.3, 13.13
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@Tag("Feature: FOR-05-07-offer-approval, task 9.4: Offer-stage controllers startup smoke")
class OfferControllersStartupSmokeTest {

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
    private OfferController offerController;

    @Autowired
    private OfferNegotiationController offerNegotiationController;

    @Autowired
    private ClientOfferController clientOfferController;

    @Autowired
    private NotificationController notificationController;

    @Test
    @DisplayName("context loads (PermissionAnnotationValidator passed) — all four Offer-stage controller beans present")
    void contextLoadsWithFullyAnnotatedControllers() {
        // Reaching this point means the SmartInitializingSingleton PermissionAnnotationValidator ran
        // during startup without throwing: none of the four controllers is half-annotated (R5.11).
        assertThat(offerController).as("OfferController bean must be injectable").isNotNull();
        assertThat(offerNegotiationController)
                .as("OfferNegotiationController bean must be injectable").isNotNull();
        assertThat(clientOfferController)
                .as("ClientOfferController bean must be injectable").isNotNull();
        assertThat(notificationController)
                .as("NotificationController bean must be injectable").isNotNull();

        assertThat(applicationContext.getBeanNamesForType(OfferController.class)).isNotEmpty();
        assertThat(applicationContext.getBeanNamesForType(OfferNegotiationController.class)).isNotEmpty();
        assertThat(applicationContext.getBeanNamesForType(ClientOfferController.class)).isNotEmpty();
        assertThat(applicationContext.getBeanNamesForType(NotificationController.class)).isNotEmpty();
    }

    @Test
    @DisplayName("the three offer controllers carry @PermissionResource(\"OFFERS\"); the bell carries \"NOTIFICATIONS\"")
    void controllersCarryExpectedPermissionResource() {
        assertResourceOf(offerController, "OFFERS");
        assertResourceOf(offerNegotiationController, "OFFERS");
        assertResourceOf(clientOfferController, "OFFERS");
        assertResourceOf(notificationController, "NOTIFICATIONS");
    }

    @Test
    @DisplayName("OfferService is project-scoped (OFFERS = offer.project.id); NotificationService is NOT")
    void serviceScoping() {
        assertThat(ProjectScopedService.class.isAssignableFrom(OfferService.class))
                .as("OfferService must be a ProjectScopedService (OFFERS is project-scoped)")
                .isTrue();
        assertThat(ProjectScopedService.class.isAssignableFrom(NotificationService.class))
                .as("NOTIFICATIONS ownership is enforced by the acting user id; NotificationService "
                        + "must NOT be project-scoped (entity-creation-rules.md step 4)")
                .isFalse();
    }

    private void assertResourceOf(Object controllerBean, String expected) {
        Class<?> targetClass = AopUtils.getTargetClass(controllerBean);
        PermissionResource permissionResource =
                AnnotatedElementUtils.findMergedAnnotation(targetClass, PermissionResource.class);
        assertThat(permissionResource)
                .as("%s must carry @PermissionResource", targetClass.getSimpleName())
                .isNotNull();
        assertThat(permissionResource.value())
                .as("%s resource code", targetClass.getSimpleName())
                .isEqualTo(expected);
    }
}
