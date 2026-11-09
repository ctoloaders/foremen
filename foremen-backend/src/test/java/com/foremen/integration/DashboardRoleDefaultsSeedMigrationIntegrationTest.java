package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.service.dashboard.GridMath;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-10 task 3.2 — role default placements (changeset 222) against the real Liquibase changelog.
 *
 * <ul>
 *   <li>every role default footprint fits the 4x9 grid and does not overlap another default of the role;</li>
 *   <li>every role default passes Has_Access (the role holds ANY operation on each resource required by
 *       the widget's dataset) under the seeded {@code role_resource_operations} matrix (ADMIN bypasses);</li>
 *   <li>no Project_Widget (SINGLE_PROJECT) is seeded as a default;</li>
 *   <li>the backfill SQL copies defaults for users without state, creates state rows, and is idempotent.</li>
 * </ul>
 *
 * <p>Dataset code -> required resources mirrors the production datasets (resource == dataset code for the
 * datasets used by the seeded library).
 *
 * <p><b>Validates: Requirements 15.1, 15.2</b>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("Feature: FOR-10-dashboard, task 3.2: role defaults seed + backfill")
class DashboardRoleDefaultsSeedMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final Set<String> SYSTEM_ROLES = Set.of(
            "ADMIN", "MANAGER", "FINANCIER", "ESTIMATOR", "SALESMAN", "FOREMAN", "WORKER", "CLIENT");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private record Placement(String role, String widget, int cell, int colSpan, int rowSpan,
                             String dataset, String scope) {
    }

    @BeforeAll
    void migrate() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        try {
            runChangelog();
        } catch (Exception first) {
            // Changeset 160 needs an ADMIN user (created by the app at startup in the real stack).
            seedAdminUser();
            runChangelog();
        }
    }

    @Test
    @DisplayName("every system role has defaults; footprints fit the grid without overlap; no project widgets")
    void footprintsFitWithoutOverlap() throws Exception {
        Map<String, List<Placement>> byRole = placements();
        assertThat(byRole.keySet()).as("roles with defaults").isSubsetOf(SYSTEM_ROLES);
        assertThat(byRole).as("seeded roles").isNotEmpty();
        byRole.forEach((role, list) -> {
            long occupied = 0L;
            for (Placement p : list) {
                assertThat(GridMath.fits(p.cell(), p.colSpan(), p.rowSpan()))
                        .as("%s: %s@%d fits the grid", role, p.widget(), p.cell()).isTrue();
                long fp = GridMath.footprint(p.cell(), p.colSpan(), p.rowSpan());
                assertThat(fp & occupied).as("%s: %s@%d overlaps another default", role, p.widget(), p.cell())
                        .isZero();
                occupied |= fp;
                assertThat(p.scope()).as("%s: %s must not be a Project_Widget", role, p.widget()).isEqualTo("NONE");
            }
        });
    }

    @Test
    @DisplayName("every role default passes Has_Access under the seeded role matrix")
    void everyDefaultIsAccessible() throws Exception {
        Map<String, Set<String>> granted = grantedResources();
        List<String> inaccessible = new ArrayList<>();
        placements().forEach((role, list) -> {
            if ("ADMIN".equals(role)) {
                return;
            }
            for (Placement p : list) {
                String resource = p.dataset();
                if (!granted.getOrDefault(role, Set.of()).contains(resource)) {
                    inaccessible.add(role + ":" + p.widget() + " (needs " + resource + ")");
                }
            }
        });
        assertThat(inaccessible).as("defaults the role cannot access").isEmpty();
    }

    @Test
    @DisplayName("backfill copies defaults for users without state, creates state, and is idempotent")
    void backfillIsIdempotent() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        try (Connection c = connection(); Statement st = c.createStatement()) {
            long workerRole = roleId(st, "WORKER");
            long clientRole = roleId(st, "CLIENT");
            long workerUser = insertUser(st, "bf-w-" + suffix, workerRole);
            long clientUser = insertUser(st, "bf-c-" + suffix, clientRole);
            // A user that already has a state row (and a deleted dashboard) must stay untouched.
            long initialized = insertUser(st, "bf-i-" + suffix, clientRole);
            st.executeUpdate("INSERT INTO user_dashboard_state (user_id, initialized_at) VALUES (" + initialized
                    + ", NOW())");

            String[] backfill = backfillSql();
            for (int run = 0; run < 2; run++) {
                for (String sql : backfill) {
                    st.executeUpdate(sql);
                }
            }

            assertThat(count(st, "SELECT COUNT(*) FROM user_dashboard_widgets WHERE user_id = " + workerUser))
                    .isEqualTo(count(st, "SELECT COUNT(*) FROM role_default_widgets WHERE role_id = " + workerRole))
                    .isEqualTo(2);
            assertThat(count(st, "SELECT COUNT(*) FROM user_dashboard_widgets WHERE user_id = " + clientUser))
                    .isEqualTo(2);
            assertThat(count(st, "SELECT COUNT(*) FROM user_dashboard_state WHERE user_id IN ("
                    + workerUser + "," + clientUser + "," + initialized + ")")).isEqualTo(3);
            assertThat(count(st, "SELECT COUNT(*) FROM user_dashboard_widgets WHERE user_id = " + initialized))
                    .isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM users u WHERE NOT EXISTS "
                    + "(SELECT 1 FROM user_dashboard_state s WHERE s.user_id = u.id)")).isZero();
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static Map<String, List<Placement>> placements() throws Exception {
        Map<String, List<Placement>> out = new HashMap<>();
        try (Connection c = connection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT r.code, w.code, d.cell_index, w.col_span, w.row_span, w.config->>'dataset', w.project_scope "
                             + "FROM role_default_widgets d JOIN roles r ON r.id = d.role_id "
                             + "JOIN dashboard_widgets w ON w.id = d.widget_id ORDER BY r.code, d.cell_index")) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Placement(
                        rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7)));
            }
        }
        return out;
    }

    /** role code -> resource codes on which the role holds at least one operation. */
    private static Map<String, Set<String>> grantedResources() throws Exception {
        Map<String, Set<String>> out = new HashMap<>();
        try (Connection c = connection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT r.code, res.code FROM role_resource_operations rro "
                             + "JOIN role_resources rr ON rr.id = rro.role_resource_id "
                             + "JOIN roles r ON r.id = rr.role_id JOIN resources res ON res.id = rr.resource_id")) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getString(1), k -> new java.util.HashSet<>()).add(rs.getString(2));
            }
        }
        return out;
    }

    /** The two backfill statements of changeset 222-backfill-dashboard-user-defaults, in order. */
    private static String[] backfillSql() throws Exception {
        Path dir = Stream.of(Path.of("database_files/changesets"), Path.of("foremen-backend/database_files/changesets"))
                .filter(Files::isDirectory).findFirst().orElseThrow();
        String xml = Files.readString(dir.resolve("222-seed-dashboard-role-defaults.xml"));
        String backfill = xml.substring(xml.indexOf("id=\"222-backfill-dashboard-user-defaults\""));
        Matcher m = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL).matcher(backfill);
        List<String> sql = new ArrayList<>();
        while (m.find()) {
            sql.add(m.group(1).trim());
        }
        assertThat(sql).hasSize(2);
        return sql.toArray(String[]::new);
    }

    private static long insertUser(Statement st, String name, long roleId) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) VALUES ('"
                        + name + "', '" + name + "@example.com', " + roleId
                        + ", true, 'pl', NOW(), 'test') RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long roleId(Statement st, String code) throws Exception {
        return count(st, "SELECT id FROM roles WHERE code = '" + code + "'");
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void runChangelog() throws Exception {
        try (Connection connection = connection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    private static void seedAdminUser() throws Exception {
        try (Connection c = connection(); Statement st = c.createStatement()) {
            long adminRoleId = count(st, "SELECT COALESCE((SELECT id FROM roles WHERE code = 'ADMIN' LIMIT 1), 0)");
            if (adminRoleId == 0
                    || count(st, "SELECT COUNT(*) FROM users u JOIN roles r ON u.role_id = r.id WHERE r.code = 'ADMIN'") > 0) {
                return;
            }
            st.executeUpdate("INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                    + "VALUES ('mig-admin', 'mig-admin@example.com', " + adminRoleId
                    + ", true, 'pl', NOW(), 'test')");
        }
    }
}
