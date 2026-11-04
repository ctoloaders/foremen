package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import java.util.regex.Pattern;

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
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * FOR-07-03 task 5.4 - Liquibase integration test for changesets {@code 207}-{@code 210}
 * (Requirements 1.1, 3.1, 10.1, 10.2).
 *
 * <p>Phase 1 applies everything before the slice and seeds a legacy project-owned procurement
 * document; phase 2 applies 207-210. Then the end state is pinned: {@code project_id} nullable, the
 * legacy row intact, category column + FK {@code ON DELETE SET NULL} (behaviourally too), and the
 * COMPANY_PURCHASES / EXPENSE_UPLOAD seeds. Finally the slice's DATABASECHANGELOG rows are removed
 * and the changelog re-run: every changeset must be {@code MARK_RAN} and nothing may change.
 *
 * <p>Repeatability: a fresh Testcontainers PostgreSQL per run; per-run unique names.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("Feature: FOR-07-03-company-procurement, task 5.4: changesets 207-210 end state + idempotency")
class Changeset207208ItTest {

    private static final Pattern SLICE_ID = Pattern.compile("^(207|208|209|210)-.*");
    private static final String SLICE_ID_SQL = "id ~ '^(207|208|209|210)-'";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private final String runId = Long.toString(System.nanoTime(), 36);
    private long legacyDocumentId;
    private long legacyProjectId;
    private List<String> slice;

    @BeforeAll
    void migrate() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        try {
            runAllExceptSlice();
        } catch (Exception first) {
            // Changeset needing an ADMIN user fails on an empty DB: seed one and resume.
            BankLedgerItSupport.execute(POSTGRES, "INSERT INTO users (name, email, role_id, active, locale, "
                    + "created_date, created_by) SELECT 'mig-admin', 'mig-admin@example.com', id, true, 'ru', "
                    + "NOW(), 'test' FROM roles WHERE code = 'ADMIN' "
                    + "AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'mig-admin@example.com')");
            runAllExceptSlice();
        }
        assertThat(slice).as("the unrun tail is exactly the FOR-07-03 slice")
                .isNotEmpty()
                .allMatch(id -> SLICE_ID.matcher(id).matches());
        assertThat(isNullable("procurement_documents", "project_id")).as("pre-207 shape: NOT NULL").isFalse();
        assertThat(columnExists("procurement_documents", "expense_category_id")).isFalse();

        // Legacy data on the pre-207 schema.
        legacyProjectId = q("INSERT INTO projects (name, status) VALUES ('mig0703 project " + runId
                + "', 'ACTIVE') RETURNING id");
        long uploaderId = q("SELECT COALESCE((SELECT id FROM users ORDER BY id LIMIT 1), 0)");
        if (uploaderId == 0) {
            uploaderId = q("INSERT INTO users (name, email, role_id, active, locale, created_date, created_by) "
                    + "SELECT 'mig0703 user', 'mig0703-" + runId + "@example.com', id, true, 'ru', NOW(), 'test' "
                    + "FROM roles WHERE code = 'ADMIN' RETURNING id");
        }
        legacyDocumentId = q("INSERT INTO procurement_documents (project_id, uploaded_by_id, raw_object_key, "
                + "source_mime_type, status, uploaded_at) VALUES (" + legacyProjectId + ", " + uploaderId
                + ", 'mig/raw-" + runId + "', 'image/png', 'PARSED', NOW()) RETURNING id");

        runChangelog();
    }

    @Test
    @Order(1)
    @DisplayName("207: project_id is nullable, the FK to projects (CASCADE) is kept; the legacy row is intact")
    void projectIdNullableAndLegacyRowIntact() throws Exception {
        assertThat(isNullable("procurement_documents", "project_id")).isTrue();
        assertThat(fkDeleteRule("fk_procurement_documents_project")).isEqualTo("c");
        assertThat(q("SELECT COUNT(*) FROM procurement_documents WHERE id = " + legacyDocumentId
                + " AND project_id = " + legacyProjectId + " AND expense_category_id IS NULL"))
                .as("legacy document keeps its project, category NULL").isEqualTo(1L);
        long uploader = q("SELECT uploaded_by_id FROM procurement_documents WHERE id = " + legacyDocumentId);
        long noProjectDoc = q("INSERT INTO procurement_documents (project_id, uploaded_by_id, raw_object_key, "
                + "source_mime_type, status, uploaded_at) VALUES (NULL, " + uploader + ", 'mig/np-" + runId
                + "', 'image/png', 'UPLOADED', NOW()) RETURNING id");
        assertThat(q("SELECT COUNT(*) FROM procurement_documents WHERE id = " + noProjectDoc
                + " AND project_id IS NULL")).isEqualTo(1L);
    }

    @Test
    @Order(2)
    @DisplayName("208: expense_category_id nullable BIGINT, FK ON DELETE SET NULL, indexes created")
    void categoryColumnFkAndIndexes() throws Exception {
        assertThat(columnExists("procurement_documents", "expense_category_id")).isTrue();
        assertThat(isNullable("procurement_documents", "expense_category_id")).isTrue();
        assertThat(fkDeleteRule("fk_procurement_documents_expense_category")).isEqualTo("n");
        assertThat(indexExists("ix_procurement_documents_category")).isTrue();
        assertThat(indexExists("ix_procurement_documents_uploaded_at")).isTrue();

        // Behavioural: deleting the category nulls the document's category, document survives.
        long categoryId = q("INSERT INTO unplanned_expense_categories (code, name_ru, name_pl) VALUES ('mig0703_"
                + runId + "', 'кат', 'kat') RETURNING id");
        BankLedgerItSupport.execute(POSTGRES, "UPDATE procurement_documents SET expense_category_id = "
                + categoryId + " WHERE id = " + legacyDocumentId);
        assertThat(q("SELECT expense_category_id FROM procurement_documents WHERE id = " + legacyDocumentId))
                .isEqualTo(categoryId);
        BankLedgerItSupport.execute(POSTGRES, "DELETE FROM unplanned_expense_categories WHERE id = " + categoryId);
        assertThat(q("SELECT COUNT(*) FROM procurement_documents WHERE id = " + legacyDocumentId
                + " AND expense_category_id IS NULL")).as("SET NULL, row survives").isEqualTo(1L);
    }

    @Test
    @Order(3)
    @DisplayName("209: COMPANY_PURCHASES resource; ADMIN/FINANCIER READ,UPDATE,DELETE; MANAGER READ")
    void companyPurchasesSeed() throws Exception {
        assertThat(q("SELECT COUNT(*) FROM resources WHERE code = 'COMPANY_PURCHASES'")).isEqualTo(1L);
        for (String role : List.of("ADMIN", "FINANCIER")) {
            assertThat(BankLedgerItSupport.operationsFor(POSTGRES, role, "COMPANY_PURCHASES"))
                    .as(role).containsExactly("DELETE", "READ", "UPDATE");
        }
        assertThat(BankLedgerItSupport.operationsFor(POSTGRES, "MANAGER", "COMPANY_PURCHASES"))
                .containsExactly("READ");
        for (String role : List.of("FOREMAN", "WORKER", "CLIENT")) {
            assertThat(BankLedgerItSupport.operationsFor(POSTGRES, role, "COMPANY_PURCHASES")).as(role).isEmpty();
        }
    }

    @Test
    @Order(4)
    @DisplayName("210: EXPENSE_UPLOAD resource; ADMIN/FINANCIER/MANAGER/FOREMAN/WORKER READ,CREATE")
    void expenseUploadSeed() throws Exception {
        assertThat(q("SELECT COUNT(*) FROM resources WHERE code = 'EXPENSE_UPLOAD'")).isEqualTo(1L);
        for (String role : List.of("ADMIN", "FINANCIER", "MANAGER", "FOREMAN", "WORKER")) {
            assertThat(BankLedgerItSupport.operationsFor(POSTGRES, role, "EXPENSE_UPLOAD"))
                    .as(role).containsExactly("CREATE", "READ");
        }
        assertThat(BankLedgerItSupport.operationsFor(POSTGRES, "CLIENT", "EXPENSE_UPLOAD")).isEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("Re-running 207-210 (changelog rows removed) marks every changeset MARK_RAN and changes nothing")
    void sliceRerunIsIdempotent() throws Exception {
        String grantsBefore = grantsFingerprint();
        long resourcesBefore = q("SELECT COUNT(*) FROM resources");
        long docsBefore = q("SELECT COUNT(*) FROM procurement_documents");
        long sliceRows = q("SELECT COUNT(*) FROM databasechangelog WHERE " + SLICE_ID_SQL);
        assertThat(sliceRows).isEqualTo(slice.size());

        BankLedgerItSupport.execute(POSTGRES, "DELETE FROM databasechangelog WHERE " + SLICE_ID_SQL);
        runChangelog();

        assertThat(q("SELECT COUNT(*) FROM databasechangelog WHERE " + SLICE_ID_SQL)).isEqualTo(sliceRows);
        assertThat(q("SELECT COUNT(*) FROM databasechangelog WHERE " + SLICE_ID_SQL
                + " AND exectype <> 'MARK_RAN' AND id NOT LIKE '%-grants'"))
                .as("schema + resource changesets are skipped as MARK_RAN").isZero();
        // Grant changesets re-execute by design (INSERT ... WHERE NOT EXISTS): they must be no-ops,
        // which the matrix fingerprint below pins.
        assertThat(grantsFingerprint()).as("matrix unchanged").isEqualTo(grantsBefore);
        assertThat(q("SELECT COUNT(*) FROM resources")).isEqualTo(resourcesBefore);
        assertThat(q("SELECT COUNT(*) FROM procurement_documents")).isEqualTo(docsBefore);
        assertThat(q("SELECT COUNT(*) FROM resources WHERE code IN ('COMPANY_PURCHASES', 'EXPENSE_UPLOAD')"))
                .isEqualTo(2L);
        assertThat(isNullable("procurement_documents", "project_id")).isTrue();
    }

    // --- harness --------------------------------------------------------------------------------

    private void runAllExceptSlice() throws Exception {
        try (Connection connection = BankLedgerItSupport.connect(POSTGRES)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(BankLedgerItSupport.CHANGELOG,
                    new ClassLoaderResourceAccessor(), database)) {
                List<ChangeSet> unrun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
                int sliceSize = 0;
                for (int i = unrun.size() - 1; i >= 0 && SLICE_ID.matcher(unrun.get(i).getId()).matches(); i--) {
                    sliceSize++;
                }
                slice = unrun.subList(unrun.size() - sliceSize, unrun.size()).stream().map(ChangeSet::getId).toList();
                int toApply = unrun.size() - sliceSize;
                if (toApply > 0) {
                    liquibase.update(toApply, new Contexts(), new LabelExpression());
                }
            }
        }
    }

    private void runChangelog() throws Exception {
        BankLedgerItSupport.runChangelog(POSTGRES);
    }

    private long q(String sql) throws Exception {
        return BankLedgerItSupport.queryForLong(POSTGRES, sql);
    }

    private boolean columnExists(String table, String column) throws Exception {
        return q("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = '" + table + "' AND column_name = '" + column + "'") > 0;
    }

    private boolean isNullable(String table, String column) throws Exception {
        return BankLedgerItSupport.queryForStrings(POSTGRES, "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?", table, column)
                .stream().anyMatch("YES"::equals);
    }

    private boolean indexExists(String name) throws Exception {
        return q("SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = '" + name + "'") > 0;
    }

    /** pg_constraint.confdeltype: c = CASCADE, n = SET NULL, r = RESTRICT, a = NO ACTION. */
    private String fkDeleteRule(String constraint) throws Exception {
        List<String> rows = BankLedgerItSupport.queryForStrings(POSTGRES,
                "SELECT confdeltype::text FROM pg_constraint WHERE conname = ?", constraint);
        assertThat(rows).as("constraint %s exists", constraint).hasSize(1);
        return rows.get(0);
    }

    private String grantsFingerprint() throws Exception {
        return String.join("|", BankLedgerItSupport.queryForStrings(POSTGRES,
                "SELECT r.code || ':' || res.code || ':' || o.code FROM role_resource_operations rro "
                        + "JOIN role_resources rr ON rro.role_resource_id = rr.id "
                        + "JOIN roles r ON rr.role_id = r.id JOIN resources res ON rr.resource_id = res.id "
                        + "JOIN operations o ON rro.operation_id = o.id ORDER BY 1"));
    }
}
