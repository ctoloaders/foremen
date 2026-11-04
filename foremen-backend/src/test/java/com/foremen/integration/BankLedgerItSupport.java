package com.foremen.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.PostgreSQLContainer;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-07-04 task 8.1 — shared plumbing of the bank-ledger integration tests that run against the real
 * Liquibase schema (changelog applied to a Testcontainers PostgreSQL, the {@code integration-test}
 * profile with Hibernate {@code ddl-auto = none}): migration with the first-ADMIN fallback used by the
 * other Liquibase-backed ITs, and small JDBC helpers.
 */
final class BankLedgerItSupport {

    static final String CHANGELOG = "database_files/changelog.xml";

    private BankLedgerItSupport() {
    }

    static Connection connect(PostgreSQLContainer<?> pg) throws Exception {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    /**
     * Applies the full changelog. A changeset that needs an existing ADMIN user fails on an empty
     * database, so on failure a bootstrap ADMIN user is inserted and the update is retried (same
     * pattern as {@code CashLedgerServiceIntegrationTest}).
     */
    static void migrate(PostgreSQLContainer<?> pg) throws Exception {
        try {
            runChangelog(pg);
        } catch (Exception first) {
            seedAdminUser(pg);
            runChangelog(pg);
        }
    }

    static void runChangelog(PostgreSQLContainer<?> pg) throws Exception {
        try (Connection connection = connect(pg)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    private static void seedAdminUser(PostgreSQLContainer<?> pg) throws Exception {
        try (Connection c = connect(pg); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id FROM roles WHERE code = 'ADMIN' LIMIT 1")) {
                if (!rs.next()) {
                    return;
                }
                long roleId = rs.getLong(1);
                st.execute("INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                        + "VALUES ('mig-admin', 'mig-admin@example.com', " + roleId + ", true, 'ru', NOW(), 'test')");
            }
        }
    }

    static long queryForLong(PostgreSQLContainer<?> pg, String sql, Object... args) throws Exception {
        try (Connection c = connect(pg); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static List<String> queryForStrings(PostgreSQLContainer<?> pg, String sql, Object... args) throws Exception {
        try (Connection c = connect(pg); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    static int execute(PostgreSQLContainer<?> pg, String sql) throws Exception {
        try (Connection c = connect(pg); Statement st = c.createStatement()) {
            return st.executeUpdate(sql);
        }
    }

    /** The operation codes a role holds on a resource in the seeded matrix, sorted. */
    static List<String> operationsFor(PostgreSQLContainer<?> pg, String role, String resource) throws Exception {
        return queryForStrings(pg, "SELECT o.code FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id "
                + "WHERE r.code = ? AND res.code = ? ORDER BY o.code", role, resource);
    }
}
