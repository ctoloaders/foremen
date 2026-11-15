package com.foremen.dao.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-11-05 task 3.6: applies the full Liquibase changelog against PostgreSQL (Testcontainers) and verifies
 * changeset 244 (tables, CHECKs, partial unique index, {@code shipments.origin_receipt_id}) and changeset 245
 * ({@code GOODS_RECEIPTS} grants: ADMIN CRUD, FOREMAN and MANAGER CRU, BUYER READ, others none), and that
 * re-executing both (their databasechangelog rows removed) changes nothing ({@code MARK_RAN}).
 *
 * <p>CHECK rows are inserted with {@code session_replication_role = replica} so foreign keys do not need a
 * whole project graph; CHECK and unique constraints stay enforced. Every inserted row is deleted in the test
 * (teardown) so the test is repeatable.
 *
 * <p>Mirrors {@code ShipmentsGrantsIntegrationTest}. Validates Requirements 1.1, 1.2, 2.1, 2.2, 2.3, 2.5.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoodsReceiptsSchemaIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";
    private static final String RESOURCE_CODE = "GOODS_RECEIPTS";
    private static final List<String> ROLES =
            List.of("ADMIN", "BUYER", "MANAGER", "FOREMAN", "FINANCIER", "CLIENT", "WORKER");
    private static final List<String> CHECKS = List.of("ck_gr_kind", "ck_gr_quantity", "ck_gr_from_accepted",
            "ck_gr_void_pair", "ck_gr_void_reason");

    private PostgreSQLContainer<?> postgres;

    @BeforeAll
    void startContainerAndMigrate() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        runLiquibase();
    }

    @AfterAll
    void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private Connection newConnection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** Changeset 160 needs an ADMIN user the changelog never seeds: seed one and resume. */
    private void runLiquibase() throws Exception {
        try {
            runChangelogOnce();
        } catch (Exception first) {
            try (Connection connection = newConnection(); var stmt = connection.createStatement()) {
                stmt.executeUpdate(
                        "INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                                + "SELECT 'mig-admin', 'mig-admin@example.com', id, true, 'ru', NOW(), 'test' "
                                + "FROM roles WHERE code = 'ADMIN'");
            }
            runChangelogOnce();
        }
    }

    private void runChangelogOnce() throws Exception {
        try (Connection connection = newConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    // ---------------------------------------------------------------- 244: structure

    @Test
    @DisplayName("244 creates the tables, the five CHECKs, the partial unique index and shipments.origin_receipt_id")
    void structureExists() throws Exception {
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN "
                + "('goods_receipts','goods_receipt_media')")).isEqualTo(2);
        for (String check : CHECKS) {
            assertThat(scalar("SELECT COUNT(*) FROM pg_constraint WHERE conname = '" + check + "'"))
                    .as(check).isEqualTo(1);
        }
        assertThat(scalar("SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'uq_gr_client_request_part' "
                + "AND indexdef LIKE '%WHERE%client_request_id IS NOT NULL%'")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'shipments' "
                + "AND column_name = 'origin_receipt_id'")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint WHERE conname = 'fk_shp_origin_receipt'")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM pg_indexes WHERE indexname IN "
                + "('ix_gr_position','ix_gr_project_occurred','ix_gr_shipment','ix_grm_receipt')")).isEqualTo(4);
    }

    // ---------------------------------------------------------------- 244: CHECKs

    private static final String INSERT = "INSERT INTO goods_receipts (project_id, position_id, kind, quantity, "
            + "from_accepted, occurred_at, received_by, client_request_id, part_no, voided_at, voided_by, void_reason) "
            + "VALUES (?, ?, ?, ?, ?, NOW(), 1, ?, ?, ?, ?, ?)";

    private record Row(String kind, String quantity, boolean fromAccepted, UUID requestId, int partNo,
                       boolean voidedAt, Long voidedBy, String voidReason) {
        static Row valid() {
            return new Row("ACCEPTED", "1.000", false, null, 0, false, null, null);
        }
        Row kind(String v) {
            return new Row(v, quantity, fromAccepted, requestId, partNo, voidedAt, voidedBy, voidReason);
        }
        Row quantity(String v) {
            return new Row(kind, v, fromAccepted, requestId, partNo, voidedAt, voidedBy, voidReason);
        }
        Row fromAccepted(boolean v) {
            return new Row(kind, quantity, v, requestId, partNo, voidedAt, voidedBy, voidReason);
        }
        Row request(UUID id, int part) {
            return new Row(kind, quantity, fromAccepted, id, part, voidedAt, voidedBy, voidReason);
        }
        Row voided(boolean at, Long by, String reason) {
            return new Row(kind, quantity, fromAccepted, requestId, partNo, at, by, reason);
        }
    }

    private void insert(Connection c, Row r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setLong(1, 9_000_001L);
            ps.setLong(2, 9_000_002L);
            ps.setString(3, r.kind());
            ps.setBigDecimal(4, new java.math.BigDecimal(r.quantity()));
            ps.setBoolean(5, r.fromAccepted());
            ps.setObject(6, r.requestId());
            ps.setInt(7, r.partNo());
            if (r.voidedAt()) {
                ps.setObject(8, java.time.OffsetDateTime.now());
            } else {
                ps.setNull(8, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            }
            if (r.voidedBy() == null) {
                ps.setNull(9, java.sql.Types.BIGINT);
            } else {
                ps.setLong(9, r.voidedBy());
            }
            ps.setString(10, r.voidReason());
            ps.executeUpdate();
        }
    }

    private Connection relaxedConnection() throws Exception {
        Connection c = newConnection();
        try (var stmt = c.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            stmt.executeUpdate("DELETE FROM goods_receipts WHERE project_id = 9000001");
        }
        return c;
    }

    @Test
    @DisplayName("CHECKs reject an unknown kind, a non-positive quantity, fromAccepted on a non-RETURNED kind and broken void columns")
    void checksRejectInvalidRows() throws Exception {
        try (Connection c = relaxedConnection()) {
            insert(c, Row.valid());
            insert(c, Row.valid().kind("RETURNED").fromAccepted(true));
            insert(c, Row.valid().kind("MISSING"));
            insert(c, Row.valid().voided(true, 1L, "wrong entry"));

            assertThatThrownBy(() -> insert(c, Row.valid().kind("BOGUS"))).hasMessageContaining("ck_gr_kind");
            assertThatThrownBy(() -> insert(c, Row.valid().quantity("0"))).hasMessageContaining("ck_gr_quantity");
            assertThatThrownBy(() -> insert(c, Row.valid().quantity("-1"))).hasMessageContaining("ck_gr_quantity");
            assertThatThrownBy(() -> insert(c, Row.valid().fromAccepted(true)))
                    .hasMessageContaining("ck_gr_from_accepted");
            assertThatThrownBy(() -> insert(c, Row.valid().kind("MISSING").fromAccepted(true)))
                    .hasMessageContaining("ck_gr_from_accepted");
            assertThatThrownBy(() -> insert(c, Row.valid().voided(true, null, "reason")))
                    .hasMessageContaining("ck_gr_void_pair");
            assertThatThrownBy(() -> insert(c, Row.valid().voided(false, 1L, null)))
                    .hasMessageContaining("ck_gr_void_pair");
            assertThatThrownBy(() -> insert(c, Row.valid().voided(true, 1L, null)))
                    .hasMessageContaining("ck_gr_void_reason");
            try (var stmt = c.createStatement()) {
                assertThat(stmt.executeUpdate("DELETE FROM goods_receipts WHERE project_id = 9000001")).as("only the four valid rows were stored").isEqualTo(4);
            }
        } finally {
            cleanup();
        }
    }

    @Test
    @DisplayName("the partial unique index rejects a repeated (client_request_id, part_no) and allows other parts and null ids")
    void clientRequestIdIsUniquePerPart() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = relaxedConnection()) {
            c.setAutoCommit(true);
            insert(c, Row.valid().request(id, 0));
            insert(c, Row.valid().request(id, 1));
            insert(c, Row.valid());
            insert(c, Row.valid());
            assertThatThrownBy(() -> insert(c, Row.valid().request(id, 0)))
                    .hasMessageContaining("uq_gr_client_request_part");
        } finally {
            cleanup();
        }
    }

    private void cleanup() throws Exception {
        try (Connection c = newConnection(); var stmt = c.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            stmt.executeUpdate("DELETE FROM goods_receipts WHERE project_id = 9000001");
            stmt.executeUpdate("DELETE FROM goods_receipts WHERE project_id = 9000001");
        }
    }

    // ---------------------------------------------------------------- 245: grants

    @Test
    @DisplayName("245 seeds one GOODS_RECEIPTS resource: ADMIN CRUD, FOREMAN/MANAGER CRU, BUYER READ, FINANCIER/CLIENT/WORKER none")
    void grantsPerRoleAreExact() throws Exception {
        assertThat(countResource()).isEqualTo(1);
        assertThat(operationsFor("ADMIN")).containsExactly("CREATE", "DELETE", "READ", "UPDATE");
        assertThat(operationsFor("FOREMAN")).containsExactly("CREATE", "READ", "UPDATE");
        assertThat(operationsFor("MANAGER")).containsExactly("CREATE", "READ", "UPDATE");
        assertThat(operationsFor("BUYER")).containsExactly("READ");
        for (String roleCode : List.of("FINANCIER", "CLIENT", "WORKER")) {
            assertThat(operationsFor(roleCode)).as(roleCode).isEmpty();
        }
    }

    // ---------------------------------------------------------------- 244 + 245: MARK_RAN re-run

    @Test
    @DisplayName("Re-running changesets 244 and 245 (rows removed from databasechangelog) changes nothing")
    void reRunIsIdempotent() throws Exception {
        int resourcesBefore = countResource();
        int roleResourcesBefore = scalar("SELECT COUNT(*) FROM role_resources rr "
                + "JOIN resources res ON rr.resource_id = res.id WHERE res.code = '" + RESOURCE_CODE + "'");
        int operationsBefore = scalar("SELECT COUNT(*) FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN resources res ON rr.resource_id = res.id WHERE res.code = '" + RESOURCE_CODE + "'");
        Map<String, List<String>> grantsBefore = snapshotGrants();
        int constraintsBefore = scalar("SELECT COUNT(*) FROM pg_constraint WHERE conname LIKE 'ck_gr_%' "
                + "OR conname LIKE 'fk_gr_%' OR conname LIKE 'fk_grm_%' OR conname = 'fk_shp_origin_receipt'");

        try (Connection c = newConnection(); var stmt = c.createStatement()) {
            int removed244 = stmt.executeUpdate("DELETE FROM databasechangelog WHERE id LIKE '244-%'");
            int removed245 = stmt.executeUpdate("DELETE FROM databasechangelog WHERE id LIKE '245-%'");
            assertThat(removed244).as("244 changesets were recorded").isPositive();
            assertThat(removed245).as("245 changesets were recorded").isEqualTo(2);
        }
        runLiquibase();

        assertThat(countResource()).isEqualTo(resourcesBefore).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM role_resources rr JOIN resources res ON rr.resource_id = res.id "
                + "WHERE res.code = '" + RESOURCE_CODE + "'")).isEqualTo(roleResourcesBefore);
        assertThat(scalar("SELECT COUNT(*) FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN resources res ON rr.resource_id = res.id WHERE res.code = '" + RESOURCE_CODE + "'"))
                .isEqualTo(operationsBefore);
        assertThat(snapshotGrants()).isEqualTo(grantsBefore);
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint WHERE conname LIKE 'ck_gr_%' "
                + "OR conname LIKE 'fk_gr_%' OR conname LIKE 'fk_grm_%' OR conname = 'fk_shp_origin_receipt'"))
                .isEqualTo(constraintsBefore);
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'shipments' "
                + "AND column_name = 'origin_receipt_id'")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private int scalar(String sql) throws Exception {
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int countResource() throws Exception {
        return scalar("SELECT COUNT(*) FROM resources WHERE code = '" + RESOURCE_CODE + "'");
    }

    private List<String> operationsFor(String roleCode) throws Exception {
        String sql = "SELECT o.code FROM role_resource_operations rro "
                + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN operations o ON rro.operation_id = o.id "
                + "WHERE r.code = ? AND res.code = ? ORDER BY o.code";
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
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

    private Map<String, List<String>> snapshotGrants() throws Exception {
        Map<String, List<String>> grants = new TreeMap<>();
        for (String roleCode : ROLES) {
            grants.put(roleCode, operationsFor(roleCode));
        }
        return grants;
    }
}
