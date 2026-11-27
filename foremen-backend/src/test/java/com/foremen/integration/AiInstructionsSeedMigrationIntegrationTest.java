package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-05-04 task 2.1 — seed changeset {@code 275-seed-ai-instructions-categories.xml}
 * (Requirement 1.5; design B1).
 *
 * <p>The changelog creates no work items, so the full changelog marks the seed as ran with nothing to
 * fill. The test then inserts categories with works, removes the {@code DATABASECHANGELOG} rows of the
 * seed (simulating a database where the catalog already exists when the changeset first runs) and
 * applies the changelog again. It proves: matching categories are filled once, an unmatched category
 * stays empty, a re-run changes nothing, and a value edited by a user is never overwritten.
 *
 * <p>Validates: Requirements 1.5
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("Feature: FOR-05-04-estimate-AI, task 2.1: aiInstructions seed of the 13 categories")
class AiInstructionsSeedMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String SEED_IDS = "275-seed-ai-instructions-categories-%";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    @BeforeAll
    void migrate() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        try {
            runChangelog();
        } catch (Exception first) {
            // Changeset 160 needs an ADMIN user on an empty DB; seed one and resume.
            seedAdminUserIfPossible();
            runChangelog();
        }
        exec("INSERT INTO measurement_units (code, name_ru, name_pl) VALUES ('ai-seed-u', 'u', 'u')");
        // Prefix 1 (preparation), prefix 13 (coordination; must NOT match the "1." rule),
        // unmatched prefix 99, and a prefix-5 category that a user already edited.
        insertCategory("ai-seed-1", "1.99");
        insertCategory("ai-seed-13", "13.99");
        insertCategory("ai-seed-99", "99.01");
        insertCategory("ai-seed-5", "5.99");
        exec("UPDATE work_categories SET ai_instructions = 'edited by user' WHERE code = 'ai-seed-5'");
        exec("DELETE FROM databasechangelog WHERE id LIKE '" + SEED_IDS + "'");
    }

    @Test
    @Order(1)
    @DisplayName("seed fills matched categories by work-code prefix, leaves unmatched and edited ones")
    void seedsOnce() throws Exception {
        runChangelog();

        assertThat(text("ai-seed-1")).contains("Protection of windows");
        assertThat(text("ai-seed-13"))
                .contains("Coordination of purchases")
                .as("prefix 13 must not match the prefix 1 rule")
                .doesNotContain("Protection of windows");
        assertThat(text("ai-seed-99")).as("unmatched category stays empty").isNull();
        assertThat(text("ai-seed-5")).as("edited value is not overwritten").isEqualTo("edited by user");
        assertThat(text("ai-seed-1")).as("no prices in the seed").doesNotContain("PLN").doesNotContain("zł");
        assertThat(text("ai-seed-1").length()).isLessThanOrEqualTo(8000);
    }

    @Test
    @Order(2)
    @DisplayName("re-running the changelog changes nothing")
    void rerunIsNoOp() throws Exception {
        String before1 = text("ai-seed-1");
        String before13 = text("ai-seed-13");
        long filledBefore = count("SELECT COUNT(*) FROM work_categories WHERE ai_instructions IS NOT NULL");

        runChangelog();

        assertThat(text("ai-seed-1")).isEqualTo(before1);
        assertThat(text("ai-seed-13")).isEqualTo(before13);
        assertThat(count("SELECT COUNT(*) FROM work_categories WHERE ai_instructions IS NOT NULL"))
                .isEqualTo(filledBefore);
    }

    @Test
    @Order(3)
    @DisplayName("an edited value is never overwritten, even if the seed changesets run again")
    void editedValueNeverOverwritten() throws Exception {
        exec("UPDATE work_categories SET ai_instructions = 'my own rules' WHERE code = 'ai-seed-1'");
        exec("DELETE FROM databasechangelog WHERE id LIKE '" + SEED_IDS + "'");

        runChangelog();

        assertThat(text("ai-seed-1")).isEqualTo("my own rules");
        assertThat(text("ai-seed-13")).contains("Coordination of purchases");
    }

    // --- helpers ---

    private static void insertCategory(String code, String workCode) throws Exception {
        exec("INSERT INTO work_categories (code, order_no, name_ru, name_pl) VALUES ('" + code
                + "', 999, 'c', 'c')");
        exec("INSERT INTO work_items (work_category_id, unit_id, name_ru, name_pl, code) VALUES ("
                + "(SELECT id FROM work_categories WHERE code = '" + code + "'), "
                + "(SELECT id FROM measurement_units WHERE code = 'ai-seed-u'), 'w', 'w', '" + workCode + "')");
    }

    private static void runChangelog() throws Exception {
        try (Connection connection = open()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    private static void seedAdminUserIfPossible() throws Exception {
        try (Connection connection = open(); Statement stmt = connection.createStatement()) {
            long adminRoleId;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COALESCE((SELECT id FROM roles WHERE code = 'ADMIN' LIMIT 1), 0)")) {
                rs.next();
                adminRoleId = rs.getLong(1);
            }
            if (adminRoleId == 0) {
                return;
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM users u JOIN roles r ON u.role_id = r.id WHERE r.code = 'ADMIN'")) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    return;
                }
            }
            stmt.executeUpdate(
                    "INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                            + "VALUES ('mig-admin', 'mig-admin@example.com', " + adminRoleId
                            + ", true, 'pl', NOW(), 'test')");
        }
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static long count(String sql) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(String categoryCode) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT ai_instructions FROM work_categories WHERE code = '" + categoryCode + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
