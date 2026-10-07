package com.foremen.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * FOR-05-07 task 1.9 — offer-approval Liquibase migration integration test.
 *
 * <p>Applies the real Liquibase changelog ({@code database_files/changelog.xml}, which registers
 * changesets {@code 128}–{@code 135} last) against a Testcontainers PostgreSQL and proves the end
 * state produced by that migration slice:
 *
 * <ul>
 *   <li>the five new tables — {@code offers}, {@code offer_discounts},
 *       {@code offer_negotiation_rounds}, {@code notifications}, {@code offer_project_settings} —
 *       exist post-apply with their documented columns (changesets 128–132);</li>
 *   <li>the {@code offers} partial unique index {@code ux_offers_active_project} enforces exactly one
 *       non-terminal offer per project — two active offers for the same project are rejected, while
 *       terminal (APPROVED/REJECTED/WITHDRAWN) offers may coexist — Requirement 1.4 (changeset 128);</li>
 *   <li>the {@code offer_discounts} CHECK {@code ck_offer_discounts_value_non_negative} rejects a
 *       negative {@code value} — Requirement 2.2 (changeset 129);</li>
 *   <li>the {@code offer_negotiation_rounds} figure-ownership CHECK
 *       {@code ck_offer_rounds_figure_ownership} (value/value_kind non-null IFF
 *       kind='MANAGER_PROPOSAL') and reject-explanation CHECK
 *       {@code ck_offer_rounds_reject_explanation} (non-blank explanation on MANAGER_REJECT) are
 *       enforced — Requirements 4.3, 4.8, 10.18 (changeset 130);</li>
 *   <li>the {@code notifications} indexes {@code ix_notifications_recipient_read} and
 *       {@code ix_notifications_recipient_created_desc} exist (changeset 131);</li>
 *   <li>the ABAC {@code OFFERS} resource + custom {@code APPROVE} operation are seeded, ADMIN and
 *       MANAGER hold CREATE/READ/UPDATE/DELETE/APPROVE, CLIENT holds READ/APPROVE, and
 *       FOREMAN/WORKER/FINANCIER hold nothing on OFFERS — Requirements 5.7, 16.6 (changeset 133);</li>
 *   <li>the ABAC {@code NOTIFICATIONS} resource is seeded with READ/UPDATE/DELETE (and no CREATE) for
 *       every one of the six roles — Requirement 13.13 (changeset 134);</li>
 *   <li>changeset 135 removed the FOREMAN/WORKER/FINANCIER grants on {@code ESTIMATE} and archived
 *       the removed rows into {@code role_resource_operations_archive} /
 *       {@code role_resources_archive} — Requirements 16.1, 16.2;</li>
 *   <li>re-running the full changelog is a no-op: no new rows, no duplicate resources/grants, no
 *       error (idempotency via {@code NOT tableExists} / {@code sqlCheck} + {@code onFail="MARK_RAN"}
 *       + per-row {@code NOT EXISTS}).</li>
 * </ul>
 *
 * <p>Harness mirrors {@code WorkerTypeMigrationSeedIntegrationTest} /
 * {@code MaterialCostNetMigrationIntegrationTest}: it drives Liquibase directly against the container
 * (no Spring context) via the Liquibase API and queries via raw JDBC, so the assertions observe
 * exactly what the migration writes. Each JVM run gets a fresh container, so it is inherently
 * repeatable with no manual cleanup.
 *
 * <p>Validates: Requirements 5.7, 13.13, 16.1, 16.2, 16.6
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfferApprovalMigrationIntegrationTest {

    private static final String CHANGELOG = "database_files/changelog.xml";

    private static final List<String> ALL_ROLES =
            List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    @BeforeAll
    void migrate() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        runChangelog();
    }

    /** Applies the full Liquibase changelog once against the running container. */
    private static void runChangelog() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
    }

    // --- schema: all five new tables exist post-apply (changesets 128–132) ---

    @Test
    @DisplayName("all five offer-approval tables exist after migrate")
    void allFiveTablesExist() throws Exception {
        assertThat(tableExists("offers")).as("128 must create offers").isTrue();
        assertThat(tableExists("offer_discounts")).as("129 must create offer_discounts").isTrue();
        assertThat(tableExists("offer_negotiation_rounds"))
                .as("130 must create offer_negotiation_rounds").isTrue();
        assertThat(tableExists("notifications")).as("131 must create notifications").isTrue();
        assertThat(tableExists("offer_project_settings"))
                .as("132 must create offer_project_settings").isTrue();
    }

    // --- schema: documented columns on each new table ---

    @Test
    @DisplayName("offers has the documented columns")
    void offersHasDocumentedColumns() throws Exception {
        assertColumns("offers",
                "id", "project_id", "estimate_id", "selected_package_id", "revision",
                "status", "approved_revision", "total_net", "total_vat", "total_gross");
    }

    @Test
    @DisplayName("offer_discounts has the documented columns")
    void offerDiscountsHasDocumentedColumns() throws Exception {
        assertColumns("offer_discounts",
                "id", "offer_id", "scope", "target_id", "kind", "value", "source_round_id");
    }

    @Test
    @DisplayName("offer_negotiation_rounds has the documented columns")
    void offerNegotiationRoundsHasDocumentedColumns() throws Exception {
        assertColumns("offer_negotiation_rounds",
                "id", "offer_id", "offer_revision", "round_no", "initiator_role", "kind",
                "scope", "target_id", "value_kind", "value", "justification", "explanation",
                "client_comment", "status", "admin_approved");
    }

    @Test
    @DisplayName("notifications has the documented columns")
    void notificationsHasDocumentedColumns() throws Exception {
        assertColumns("notifications",
                "id", "recipient_id", "type", "body", "deep_link", "read");
    }

    @Test
    @DisplayName("offer_project_settings has the documented columns")
    void offerProjectSettingsHasDocumentedColumns() throws Exception {
        assertColumns("offer_project_settings",
                "id", "project_id", "escalation_percent_cap", "escalation_absolute_cap");
    }

    // --- offers partial unique index: one non-terminal offer per project (R1.4) ---

    @Test
    @DisplayName("ux_offers_active_project allows only one non-terminal offer per project")
    void partialUniqueIndexEnforcesOneActiveOfferPerProject() throws Exception {
        assertThat(indexExists("ux_offers_active_project"))
                .as("128 must create the partial unique index")
                .isTrue();

        long projectId = seedProjectWithEstimate();
        try {
            // First non-terminal (DRAFT) offer for the project succeeds.
            insertOffer(projectId, "DRAFT");

            // A SECOND non-terminal offer for the same project must be rejected by the index.
            assertThat(tryInsertOffer(projectId, "SENT"))
                    .as("a second non-terminal offer for the same project must violate ux_offers_active_project")
                    .isFalse();

            // Terminal offers may coexist freely alongside the active one.
            assertThat(tryInsertOffer(projectId, "APPROVED"))
                    .as("a terminal APPROVED offer may coexist with the active one")
                    .isTrue();
            assertThat(tryInsertOffer(projectId, "REJECTED"))
                    .as("a terminal REJECTED offer may coexist")
                    .isTrue();
            assertThat(tryInsertOffer(projectId, "WITHDRAWN"))
                    .as("a terminal WITHDRAWN offer may coexist")
                    .isTrue();
        } finally {
            cleanupOffers(projectId);
        }
    }

    // --- offer_discounts: value >= 0 CHECK (R2.2) ---

    @Test
    @DisplayName("ck_offer_discounts_value_non_negative rejects a negative discount value")
    void discountValueNonNegativeCheckIsEnforced() throws Exception {
        long projectId = seedProjectWithEstimate();
        try {
            long offerId = insertOffer(projectId, "DRAFT");

            // A non-negative value is accepted.
            assertThat(tryInsertDiscount(offerId, "0.0000"))
                    .as("a zero discount value must be accepted")
                    .isTrue();
            assertThat(tryInsertDiscount(offerId, "10.5000"))
                    .as("a positive discount value must be accepted")
                    .isTrue();

            // A negative value must be rejected by the CHECK constraint.
            assertThat(tryInsertDiscount(offerId, "-0.0001"))
                    .as("a negative discount value must violate ck_offer_discounts_value_non_negative")
                    .isFalse();
        } finally {
            cleanupOffers(projectId);
        }
    }

    // --- offer_negotiation_rounds: figure-ownership + reject-explanation CHECKs (R4.3/R4.8/R10.18) ---

    @Test
    @DisplayName("ck_offer_rounds_figure_ownership enforces value/value_kind only on MANAGER_PROPOSAL")
    void roundFigureOwnershipCheckIsEnforced() throws Exception {
        long projectId = seedProjectWithEstimate();
        try {
            long offerId = insertOffer(projectId, "DRAFT");

            // MANAGER_PROPOSAL with a figure is valid.
            assertThat(tryInsertRound(offerId, "MANAGER_PROPOSAL", "PERCENT", "10.0000", null))
                    .as("a MANAGER_PROPOSAL carrying value + value_kind is valid")
                    .isTrue();
            // A DISCOUNT_REQUEST with NO figure is valid.
            assertThat(tryInsertRound(offerId, "DISCOUNT_REQUEST", null, null, null))
                    .as("a DISCOUNT_REQUEST with no figure is valid")
                    .isTrue();

            // MANAGER_PROPOSAL WITHOUT a figure violates figure-ownership.
            assertThat(tryInsertRound(offerId, "MANAGER_PROPOSAL", null, null, null))
                    .as("a MANAGER_PROPOSAL missing its figure must violate ck_offer_rounds_figure_ownership")
                    .isFalse();
            // A non-proposal carrying a figure violates figure-ownership.
            assertThat(tryInsertRound(offerId, "DISCOUNT_REQUEST", "PERCENT", "10.0000", null))
                    .as("a DISCOUNT_REQUEST carrying a figure must violate ck_offer_rounds_figure_ownership")
                    .isFalse();
        } finally {
            cleanupOffers(projectId);
        }
    }

    @Test
    @DisplayName("ck_offer_rounds_reject_explanation requires a non-blank explanation on MANAGER_REJECT")
    void roundRejectExplanationCheckIsEnforced() throws Exception {
        long projectId = seedProjectWithEstimate();
        try {
            long offerId = insertOffer(projectId, "DRAFT");

            // MANAGER_REJECT with a non-blank explanation is valid.
            assertThat(tryInsertRound(offerId, "MANAGER_REJECT", null, null, "too high"))
                    .as("a MANAGER_REJECT with a non-blank explanation is valid")
                    .isTrue();

            // MANAGER_REJECT with a NULL explanation is rejected.
            assertThat(tryInsertRound(offerId, "MANAGER_REJECT", null, null, null))
                    .as("a MANAGER_REJECT with a null explanation must violate ck_offer_rounds_reject_explanation")
                    .isFalse();
            // MANAGER_REJECT with a BLANK (whitespace-only) explanation is rejected.
            assertThat(tryInsertRound(offerId, "MANAGER_REJECT", null, null, "   "))
                    .as("a MANAGER_REJECT with a blank explanation must violate ck_offer_rounds_reject_explanation")
                    .isFalse();
        } finally {
            cleanupOffers(projectId);
        }
    }

    // --- notifications indexes (changeset 131) ---

    @Test
    @DisplayName("notifications recipient indexes exist")
    void notificationIndexesExist() throws Exception {
        assertThat(indexExists("ix_notifications_recipient_read"))
                .as("131 must create the unread-count index").isTrue();
        assertThat(indexExists("ix_notifications_recipient_created_desc"))
                .as("131 must create the list index").isTrue();
    }

    // --- ABAC: OFFERS resource + APPROVE operation + CLIENT/MANAGER/ADMIN grants (R5.7, R16.6) ---

    @Test
    @DisplayName("OFFERS resource and custom APPROVE operation are seeded")
    void offersResourceAndApproveOperationSeeded() throws Exception {
        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'OFFERS'"))
                .as("the OFFERS resource must be seeded").isEqualTo(1L);
        assertThat(queryForLong("SELECT COUNT(*) FROM operations WHERE code = 'APPROVE'"))
                .as("the custom APPROVE operation must be seeded").isEqualTo(1L);
    }

    @Test
    @DisplayName("ADMIN and MANAGER hold full CRUD+APPROVE, CLIENT holds READ+APPROVE on OFFERS")
    void offersGrantsSeededPerTightenedMatrix() throws Exception {
        assertThat(operationsFor("ADMIN", "OFFERS"))
                .as("ADMIN OFFERS grants")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE", "APPROVE");
        assertThat(operationsFor("MANAGER", "OFFERS"))
                .as("MANAGER OFFERS grants")
                .containsExactlyInAnyOrder("CREATE", "READ", "UPDATE", "DELETE", "APPROVE");
        assertThat(operationsFor("CLIENT", "OFFERS"))
                .as("CLIENT OFFERS grants")
                .containsExactlyInAnyOrder("READ", "APPROVE");
    }

    @Test
    @DisplayName("FOREMAN/WORKER/FINANCIER hold no OFFERS grant")
    void offersHasNoGrantForExecutorReadRoles() throws Exception {
        assertThat(operationsFor("FOREMAN", "OFFERS")).as("FOREMAN OFFERS grants").isEmpty();
        assertThat(operationsFor("WORKER", "OFFERS")).as("WORKER OFFERS grants").isEmpty();
        assertThat(operationsFor("FINANCIER", "OFFERS")).as("FINANCIER OFFERS grants").isEmpty();
    }

    // --- ABAC: NOTIFICATIONS all-roles READ/UPDATE/DELETE, no CREATE (R13.13) ---

    @Test
    @DisplayName("NOTIFICATIONS resource is seeded")
    void notificationsResourceSeeded() throws Exception {
        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'NOTIFICATIONS'"))
                .as("the NOTIFICATIONS resource must be seeded").isEqualTo(1L);
    }

    @Test
    @DisplayName("every role holds READ/UPDATE/DELETE (and no CREATE) on NOTIFICATIONS")
    void notificationsGrantsSeededForEveryRole() throws Exception {
        for (String role : ALL_ROLES) {
            assertThat(operationsFor(role, "NOTIFICATIONS"))
                    .as("%s must hold READ/UPDATE/DELETE and no CREATE on NOTIFICATIONS", role)
                    .containsExactlyInAnyOrder("READ", "UPDATE", "DELETE");
        }
    }

    // --- 135: FOREMAN/WORKER/FINANCIER ESTIMATE grants removed + archived (R16.1, R16.2) ---

    @Test
    @DisplayName("changeset 135 removed the FOREMAN/WORKER/FINANCIER ESTIMATE grants")
    void estimateReadGrantsRemovedForExecutorReadRoles() throws Exception {
        assertThat(operationsFor("FOREMAN", "ESTIMATE")).as("FOREMAN ESTIMATE grants").isEmpty();
        assertThat(operationsFor("WORKER", "ESTIMATE")).as("WORKER ESTIMATE grants").isEmpty();
        assertThat(operationsFor("FINANCIER", "ESTIMATE")).as("FINANCIER ESTIMATE grants").isEmpty();

        assertThat(queryForLong(
                "SELECT COUNT(*) FROM role_resources rr "
                        + "JOIN roles r ON rr.role_id = r.id "
                        + "JOIN resources res ON rr.resource_id = res.id "
                        + "WHERE r.code IN ('FOREMAN','WORKER','FINANCIER') AND res.code = 'ESTIMATE'"))
                .as("no FOREMAN/WORKER/FINANCIER role_resources row on ESTIMATE may remain")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("changeset 135 archived the removed ESTIMATE grants into the archive tables")
    void removedEstimateGrantsWereArchived() throws Exception {
        assertThat(tableExists("role_resource_operations_archive"))
                .as("135a must create role_resource_operations_archive").isTrue();
        assertThat(tableExists("role_resources_archive"))
                .as("135b must create role_resources_archive").isTrue();

        // The removed ESTIMATE role_resources rows for the three roles were snapshotted.
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM role_resources_archive "
                        + "WHERE role_code IN ('FOREMAN','WORKER','FINANCIER') AND resource_code = 'ESTIMATE'"))
                .as("the removed ESTIMATE role_resources rows must be archived")
                .isEqualTo(3L);

        // The removed ESTIMATE operation grants for the three roles were snapshotted.
        assertThat(queryForLong(
                "SELECT COUNT(*) FROM role_resource_operations_archive "
                        + "WHERE role_code IN ('FOREMAN','WORKER','FINANCIER') AND resource_code = 'ESTIMATE'"))
                .as("the removed ESTIMATE operation grants must be archived")
                .isGreaterThan(0L);
    }

    // --- 136: the ESTIMATOR role + its FOREMAN-equivalent + OFFERS/ESTIMATE grants (R16.7, R16.8, R16.1, R16.3) ---

    @Test
    @DisplayName("changeset 136 seeds the ESTIMATOR role")
    void estimatorRoleSeeded() throws Exception {
        assertThat(queryForLong("SELECT COUNT(*) FROM roles WHERE code = 'ESTIMATOR'"))
                .as("the ESTIMATOR role must be seeded by changeset 136")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("ESTIMATOR holds OFFERS READ/CREATE/UPDATE and NOT APPROVE/DELETE")
    void estimatorOffersGrantsSeeded() throws Exception {
        assertThat(operationsFor("ESTIMATOR", "OFFERS"))
                .as("ESTIMATOR OFFERS grants must be READ/CREATE/UPDATE only (no APPROVE, no DELETE)")
                .containsExactlyInAnyOrder("READ", "CREATE", "UPDATE");
    }

    @Test
    @DisplayName("ESTIMATOR holds ESTIMATE READ/CREATE/UPDATE")
    void estimatorEstimateGrantsSeeded() throws Exception {
        assertThat(operationsFor("ESTIMATOR", "ESTIMATE"))
                .as("ESTIMATOR ESTIMATE grants must be READ/CREATE/UPDATE")
                .containsExactlyInAnyOrder("READ", "CREATE", "UPDATE");
    }

    @Test
    @DisplayName("ESTIMATOR mirrors FOREMAN's full grant set (plus OFFERS/ESTIMATE it adds)")
    void estimatorMirrorsForemanGrantSet() throws Exception {
        // For every resource FOREMAN holds, ESTIMATOR must hold the SAME operation set — proving the
        // INSERT ... SELECT replication copied FOREMAN's grants exactly. OFFERS/ESTIMATE are the two
        // grants FOREMAN lacks (added separately) and are asserted by the tests above, so they are
        // excluded here.
        for (String resource : resourcesHeldBy("FOREMAN")) {
            if (resource.equals("OFFERS") || resource.equals("ESTIMATE")) {
                continue;
            }
            assertThat(operationsFor("ESTIMATOR", resource))
                    .as("ESTIMATOR must mirror FOREMAN's operations on %s", resource)
                    .containsExactlyInAnyOrderElementsOf(operationsFor("FOREMAN", resource));
        }
    }

    // --- idempotency: re-running 128–135 is a no-op ---

    @Test
    @DisplayName("re-running the changelog is a no-op (no new resources/grants/archives, no error)")
    void reRunIsNoOp() throws Exception {
        long offersResBefore = queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'OFFERS'");
        long notifResBefore = queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'NOTIFICATIONS'");
        long approveOpBefore = queryForLong("SELECT COUNT(*) FROM operations WHERE code = 'APPROVE'");
        int adminOffersBefore = operationsFor("ADMIN", "OFFERS").size();
        int clientOffersBefore = operationsFor("CLIENT", "OFFERS").size();
        int clientNotifBefore = operationsFor("CLIENT", "NOTIFICATIONS").size();
        long rroArchiveBefore = queryForLong("SELECT COUNT(*) FROM role_resource_operations_archive");
        long rrArchiveBefore = queryForLong("SELECT COUNT(*) FROM role_resources_archive");

        // Apply the same changelog a second time — must not error, must not change anything.
        runChangelog();

        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'OFFERS'"))
                .as("re-run must not duplicate the OFFERS resource").isEqualTo(offersResBefore).isEqualTo(1L);
        assertThat(queryForLong("SELECT COUNT(*) FROM resources WHERE code = 'NOTIFICATIONS'"))
                .as("re-run must not duplicate the NOTIFICATIONS resource").isEqualTo(notifResBefore).isEqualTo(1L);
        assertThat(queryForLong("SELECT COUNT(*) FROM operations WHERE code = 'APPROVE'"))
                .as("re-run must not duplicate the APPROVE operation").isEqualTo(approveOpBefore).isEqualTo(1L);
        assertThat(operationsFor("ADMIN", "OFFERS").size())
                .as("re-run must not duplicate ADMIN OFFERS grants").isEqualTo(adminOffersBefore);
        assertThat(operationsFor("CLIENT", "OFFERS").size())
                .as("re-run must not duplicate CLIENT OFFERS grants").isEqualTo(clientOffersBefore);
        assertThat(operationsFor("CLIENT", "NOTIFICATIONS").size())
                .as("re-run must not duplicate CLIENT NOTIFICATIONS grants").isEqualTo(clientNotifBefore);
        assertThat(queryForLong("SELECT COUNT(*) FROM role_resource_operations_archive"))
                .as("re-run must not re-archive operation grants").isEqualTo(rroArchiveBefore);
        assertThat(queryForLong("SELECT COUNT(*) FROM role_resources_archive"))
                .as("re-run must not re-archive role_resources rows").isEqualTo(rrArchiveBefore);
    }

    // --- fixtures: a minimal project + PRICED estimate to satisfy the offers FKs ---

    /**
     * Inserts a minimal {@code projects} + {@code estimates} pair (with a get-or-created reference
     * {@code currencies} row for the estimate's NOT NULL {@code currency_id} FK) and returns the
     * project id. The offers table has NOT NULL FKs to both project and estimate, so the index/CHECK
     * probes need real parent rows. Only the NOT NULL columns are populated; the rest default. Uses a
     * unique project name per call so the fixture is repeatable across runs without cleanup.
     */
    private long seedProjectWithEstimate() throws Exception {
        long currencyId = getOrCreateCurrencyId();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement()) {
            long projectId;
            try (ResultSet rs = stmt.executeQuery(
                    "INSERT INTO projects (name, status, created_date, created_by) "
                            + "VALUES ('offer-mig-" + System.nanoTime() + "', 'DRAFT', NOW(), 'test') "
                            + "RETURNING id")) {
                rs.next();
                projectId = rs.getLong(1);
            }
            try (var estStmt = connection.createStatement()) {
                estStmt.executeUpdate(
                        "INSERT INTO estimates (project_id, currency_id, status, created_date, created_by) "
                                + "VALUES (" + projectId + ", " + currencyId + ", 'PRICED', NOW(), 'test')");
            }
            return projectId;
        }
    }

    /**
     * Get-or-create a reference {@code currencies} row (code {@code PLN}) for the estimate FK. The
     * catalog is a fixed global constant reused across fixture calls, so it is created at most once.
     */
    private long getOrCreateCurrencyId() throws Exception {
        long existing = queryForLong("SELECT COALESCE((SELECT id FROM currencies WHERE code = 'PLN'), 0)");
        if (existing > 0) {
            return existing;
        }
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "INSERT INTO currencies (code, symbol, name_ru, name_pl, created_date, created_by) "
                             + "VALUES ('PLN', 'zł', 'Злотый', 'Złoty', NOW(), 'test') RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long estimateIdForProject(long projectId) throws Exception {
        return queryForLong("SELECT id FROM estimates WHERE project_id = " + projectId + " LIMIT 1");
    }

    /** Inserts an offer with the given status; asserts it succeeded and returns its id. */
    private long insertOffer(long projectId, String status) throws Exception {
        long estimateId = estimateIdForProject(projectId);
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "INSERT INTO offers (project_id, estimate_id, revision, status, "
                             + "total_net, total_vat, total_gross, created_date, created_by) "
                             + "VALUES (" + projectId + ", " + estimateId + ", 1, '" + status + "', "
                             + "0, 0, 0, NOW(), 'test') RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Attempts to insert an offer; returns true if it succeeded, false if a constraint rejected it. */
    private boolean tryInsertOffer(long projectId, String status) throws Exception {
        try {
            insertOffer(projectId, status);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryInsertDiscount(long offerId, String value) throws Exception {
        return tryExecute(
                "INSERT INTO offer_discounts (offer_id, scope, kind, value, created_date, created_by) "
                        + "VALUES (" + offerId + ", 'GLOBAL', 'PERCENT', " + value + ", NOW(), 'test')");
    }

    private boolean tryInsertRound(
            long offerId, String kind, String valueKind, String value, String explanation)
            throws Exception {
        String valueKindSql = valueKind == null ? "NULL" : "'" + valueKind + "'";
        String valueSql = value == null ? "NULL" : value;
        String explanationSql = explanation == null ? "NULL" : "'" + explanation + "'";
        return tryExecute(
                "INSERT INTO offer_negotiation_rounds (offer_id, offer_revision, round_no, "
                        + "initiator_role, kind, value_kind, value, explanation, status, admin_approved, "
                        + "created_date, created_by) "
                        + "VALUES (" + offerId + ", 1, 1, 'MANAGER', '" + kind + "', "
                        + valueKindSql + ", " + valueSql + ", " + explanationSql
                        + ", 'OPEN', false, NOW(), 'test')");
    }

    private void cleanupOffers(long projectId) throws Exception {
        // ON DELETE CASCADE from projects removes the offer, its discounts, and its rounds; the
        // estimate row goes with the project too. Deleting the project keeps the shared catalog clean.
        execute("DELETE FROM projects WHERE id = " + projectId);
    }

    // --- helpers ---

    private boolean tableExists(String table) throws Exception {
        return queryForBoolean(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = '" + table + "')");
    }

    private boolean columnExists(String table, String column) throws Exception {
        return queryForBoolean(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                        + "AND column_name = '" + column + "')");
    }

    private void assertColumns(String table, String... columns) throws Exception {
        for (String column : columns) {
            assertThat(columnExists(table, column))
                    .as("%s must have column %s", table, column)
                    .isTrue();
        }
    }

    private boolean indexExists(String indexName) throws Exception {
        return queryForBoolean(
                "SELECT EXISTS (SELECT 1 FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND indexname = '" + indexName + "')");
    }

    private List<String> operationsFor(String roleCode, String resourceCode) throws Exception {
        List<String> operations = new ArrayList<>();
        String sql = "SELECT o.code "
                + "FROM role_resources rr "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "JOIN role_resource_operations rro ON rro.role_resource_id = rr.id "
                + "JOIN operations o ON o.id = rro.operation_id "
                + "WHERE r.code = '" + roleCode + "' AND res.code = '" + resourceCode + "'";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                operations.add(rs.getString("code"));
            }
        }
        return operations;
    }

    /** Returns the resource codes a role holds any grant on. */
    private List<String> resourcesHeldBy(String roleCode) throws Exception {
        List<String> resources = new ArrayList<>();
        String sql = "SELECT DISTINCT res.code "
                + "FROM role_resources rr "
                + "JOIN roles r ON rr.role_id = r.id "
                + "JOIN resources res ON rr.resource_id = res.id "
                + "WHERE r.code = '" + roleCode + "'";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                resources.add(rs.getString("code"));
            }
        }
        return resources;
    }

    private long queryForLong(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private boolean queryForBoolean(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    /** Executes a mutating statement; returns true on success, false if a DB constraint rejected it. */
    private boolean tryExecute(String sql) throws Exception {
        try {
            execute(sql);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
