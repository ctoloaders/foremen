package com.foremen.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.config.security.PermissionResource;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-07-01 task 7.1 — combined <b>startup + seed</b> integration test for the cross-project
 * staffing projection's ABAC wiring. It proves, in one booted context against a real PostgreSQL
 * (Testcontainers), the two guarantees the design mandates for the read-only
 * {@code COMPANY_SCHEDULE} resource:
 *
 * <ol>
 *   <li><b>Startup completeness (Requirements 9.4, {@code PermissionAnnotationValidator}).</b> The
 *       full Spring application context loads. Because the
 *       {@link com.foremen.config.security.PermissionAnnotationValidator} is a
 *       {@link org.springframework.beans.factory.SmartInitializingSingleton} that runs once after
 *       all singletons are instantiated and <em>throws</em> if any guarded handler is
 *       half-annotated, a context that loads without error is proof the validator classified the
 *       {@code @PermissionResource("COMPANY_SCHEDULE")} controller — whose single {@code GET}
 *       handler carries {@code @PermissionOperation("READ")} — as COMPLETE. The test also asserts
 *       the controller still carries exactly {@code @PermissionResource("COMPANY_SCHEDULE")}.</li>
 *   <li><b>Seeded ABAC matrix (Requirements 9.1, 9.2, 9.5).</b> Unlike the sibling startup smoke
 *       tests (which run {@code create-drop} with Liquibase disabled), this test applies the real
 *       Liquibase changelog against the Testcontainers database in a {@code @BeforeAll} (switching
 *       Hibernate to {@code ddl-auto: none} so the booted context reuses that migrated schema) so
 *       changeset {@code 187-seed-company-schedule-resource} runs. It then asserts, via raw JDBC
 *       against the live catalog: the {@code COMPANY_SCHEDULE} resource row exists exactly once; both ADMIN and
 *       MANAGER hold a {@code READ} grant on it; and <b>no</b> role holds any {@code CREATE} /
 *       {@code UPDATE} / {@code DELETE} grant on it (it is a read-only resource).</li>
 * </ol>
 *
 * <p>Mirrors the boot pattern of {@link OfferControllersStartupSmokeTest} /
 * {@link WorkReportControllerStartupSmokeTest} for the validator guarantee and the raw-JDBC
 * grant-snapshot pattern of {@code ProjectScheduleMigrationIntegrationTest} for the seed assertions.
 *
 * <p>Feature: FOR-07-01-cross-project-schedule-core
 *
 * <p>Validates: Requirements 9.1, 9.2, 9.4, 9.5
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Tag("Feature: FOR-07-01-cross-project-schedule-core, task 7.1: COMPANY_SCHEDULE startup + seed")
class CrossProjectScheduleControllerStartupSeedIntegrationTest {

    private static final String RESOURCE_CODE = "COMPANY_SCHEDULE";

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
        // The container schema is built by the REAL Liquibase changelog in migrateContainer()
        // (@BeforeAll, which runs after the @Container starts and before the Spring context boots),
        // so changeset 187 seeds the COMPANY_SCHEDULE resource + grants. The Spring context must NOT
        // re-own the schema: keep Hibernate off (ddl-auto: none) so it neither drops nor recreates
        // the migrated schema the seed assertions read.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    /**
     * Applies the full Liquibase changelog (including changeset 187) against the Testcontainers
     * database before the Spring context boots. {@code @Container} starts the Postgres instance
     * before any {@code @BeforeAll}, and Spring's {@code @DynamicPropertySource} points the booted
     * context at this same already-migrated database (with {@code ddl-auto: none} and Liquibase
     * disabled by the {@code integration-test} profile), so the seeded rows are present both for the
     * startup guarantee and for the raw-JDBC seed assertions below. Mirrors the Liquibase bootstrap
     * of {@code ProjectScheduleMigrationIntegrationTest}.
     *
     * <p>The changelog is applied in two phases around a bootstrap ADMIN user: several document-seed
     * changesets (from {@code 160-seed-document-templates.xml} onward) resolve their
     * {@code uploaded_by_id} from the lowest-id ADMIN-role user — which, in production, is provisioned
     * at runtime by {@code AdminBootstrap} ({@code foremen.admin.create=true}), not by Liquibase. On a
     * freshly migrated database with no user, that subquery is {@code NULL} and the seed violates a
     * {@code NOT NULL} constraint. So this test applies every changeset up to (but not including) the
     * first {@code 160-*} changeset, inserts one ACTIVE ADMIN-role user (standing in for the runtime
     * bootstrap), then applies the remainder — including changeset 187. This is purely a test harness
     * concern; no production changeset is modified.
     */
    @BeforeAll
    static void migrateContainer() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "database_files/changelog.xml", new ClassLoaderResourceAccessor(), database)) {

                // Phase 1: apply every unrun changeset preceding the first user-dependent document
                // seed (id starts with "160-"). listUnrunChangeSets returns pending changesets in
                // apply order.
                liquibase.LabelExpression labels = new liquibase.LabelExpression();
                List<liquibase.changelog.ChangeSet> unrun =
                        liquibase.listUnrunChangeSets(new Contexts(), labels);
                int preUserSeed = 0;
                for (liquibase.changelog.ChangeSet cs : unrun) {
                    if (cs.getId().startsWith("160-")) {
                        break;
                    }
                    preUserSeed++;
                }
                liquibase.update(preUserSeed, new Contexts(), labels);
            }
        }

        // Insert the bootstrap ADMIN user the later document seeds depend on (runtime AdminBootstrap
        // stand-in). The users / roles tables and the ADMIN role now exist from phase 1.
        seedBootstrapAdminUser();

        // Phase 2: apply the remaining changesets, now that an ADMIN user exists — includes 187.
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "database_files/changelog.xml", new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    /** Inserts exactly one ACTIVE ADMIN-role user so the user-dependent document seeds resolve. */
    private static void seedBootstrapAdminUser() throws Exception {
        String sql = "INSERT INTO users "
                + "(name, email, password_hash, role_id, active, locale, status, created_date, created_by) "
                + "SELECT 'Startup Seed Admin', 'startup-seed-admin@example.com', 'x', r.id, "
                + "       true, 'ru', 'ACTIVE', NOW(), 'startup-seed-test' "
                + "FROM roles r WHERE r.code = 'ADMIN' "
                + "AND NOT EXISTS (SELECT 1 FROM users u JOIN roles r2 ON u.role_id = r2.id WHERE r2.code = 'ADMIN')";
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CrossProjectScheduleController crossProjectScheduleController;

    @Autowired
    private DataSource dataSource;

    // --- Requirement 9.4 : context loads => PermissionAnnotationValidator passed for the controller ---

    @Test
    @DisplayName("context loads (PermissionAnnotationValidator passed) — CrossProjectScheduleController "
            + "with its READ handler is fully annotated")
    void contextLoadsWithFullyAnnotatedController() {
        // Reaching this point means the SmartInitializingSingleton PermissionAnnotationValidator ran
        // during startup without throwing: the @PermissionResource("COMPANY_SCHEDULE") controller's
        // single GET handler carries a matching @PermissionOperation("READ"), so it is not
        // half-annotated (Requirement 9.4).
        assertThat(crossProjectScheduleController)
                .as("CrossProjectScheduleController bean must be injectable").isNotNull();
        assertThat(applicationContext.getBeanNamesForType(CrossProjectScheduleController.class))
                .as("CrossProjectScheduleController bean must be registered").isNotEmpty();
    }

    @Test
    @DisplayName("CrossProjectScheduleController carries @PermissionResource(\"COMPANY_SCHEDULE\")")
    void controllerCarriesExpectedPermissionResource() {
        Class<?> targetClass = AopUtils.getTargetClass(crossProjectScheduleController);
        PermissionResource permissionResource =
                AnnotatedElementUtils.findMergedAnnotation(targetClass, PermissionResource.class);
        assertThat(permissionResource)
                .as("CrossProjectScheduleController must carry @PermissionResource").isNotNull();
        assertThat(permissionResource.value()).isEqualTo(RESOURCE_CODE);
    }

    // --- Requirements 9.1, 9.2, 9.5 : changeset 187 seeds the read-only resource + READ-only grants ---

    @Test
    @DisplayName("Changeset 187 seeds the COMPANY_SCHEDULE resource exactly once")
    void seedsCompanyScheduleResource() throws Exception {
        assertThat(countResource(RESOURCE_CODE))
                .as("COMPANY_SCHEDULE resource row exists exactly once").isEqualTo(1);
    }

    @Test
    @DisplayName("ADMIN and MANAGER hold a READ grant on COMPANY_SCHEDULE")
    void adminAndManagerHoldReadGrant() throws Exception {
        assertThat(operationsFor("ADMIN"))
                .as("ADMIN operations on COMPANY_SCHEDULE")
                .containsExactly("READ");
        assertThat(operationsFor("MANAGER"))
                .as("MANAGER operations on COMPANY_SCHEDULE")
                .containsExactly("READ");
    }

    @Test
    @DisplayName("No role holds a CREATE/UPDATE/DELETE grant on COMPANY_SCHEDULE (read-only resource)")
    void noWriteGrantOnCompanyScheduleForAnyRole() throws Exception {
        assertThat(rolesWithOperation("CREATE"))
                .as("no role may hold CREATE on COMPANY_SCHEDULE").isEmpty();
        assertThat(rolesWithOperation("UPDATE"))
                .as("no role may hold UPDATE on COMPANY_SCHEDULE").isEmpty();
        assertThat(rolesWithOperation("DELETE"))
                .as("no role may hold DELETE on COMPANY_SCHEDULE").isEmpty();
    }

    // ---------------------------------------------------------------------
    // seed / grant query helpers (raw JDBC against the live catalog)
    // ---------------------------------------------------------------------

    private int countResource(String code) throws Exception {
        String sql = "SELECT COUNT(*) FROM resources WHERE code = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Sorted operation codes granted to {@code roleCode} on COMPANY_SCHEDULE. */
    private List<String> operationsFor(String roleCode) throws Exception {
        String sql = "SELECT o.code FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id "
                + "WHERE r.code = ? AND res.code = ? "
                + "ORDER BY o.code";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, roleCode);
            ps.setString(2, RESOURCE_CODE);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ops = new ArrayList<>();
                while (rs.next()) {
                    ops.add(rs.getString(1));
                }
                return ops;
            }
        }
    }

    /** All role codes holding the given operation on COMPANY_SCHEDULE (must be empty for writes). */
    private List<String> rolesWithOperation(String operationCode) throws Exception {
        String sql = "SELECT r.code FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id "
                + "WHERE res.code = ? AND o.code = ? "
                + "ORDER BY r.code";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, RESOURCE_CODE);
            ps.setString(2, operationCode);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> roles = new ArrayList<>();
                while (rs.next()) {
                    roles.add(rs.getString(1));
                }
                return roles;
            }
        }
    }
}
