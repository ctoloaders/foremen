package com.foremen.qa.fixtures;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * Direct-DB fixture for the FOR-05-07b offers slice (FOR-QA-AUTO-05-07B), in the style of
 * {@link WorkReportFixture}.
 *
 * <p>The estimate itself is built through the API by the steps. This fixture covers only what the
 * API cannot do on the docker stack: lock the estimate (leave DRAFT), mark a package as applied
 * (the Save path that sets it needs a browser session), insert legacy offers without a snapshot or
 * with a version 1 snapshot, and read back estimate rows, audit rows and schema facts for the
 * assertions. All SQL is parameterized. Everything written here belongs to a run-created project,
 * so the project delete registered by the steps removes it (offers and estimates cascade).
 */
public final class OfferEstimateFixture {

    private final DataSource dataSource;

    public OfferEstimateFixture() {
        this.dataSource = Db.dataSource();
    }

    // ---- writes ----

    /** Set the estimate lifecycle status, for example DRAFT or PRICED. */
    public void setEstimateStatus(long estimateId, String status) {
        update("UPDATE estimates SET status = ? WHERE id = ?", status, estimateId);
    }

    /** Mark a package code as applied to the estimate (the package marker source), or clear it. */
    public void setAppliedPackage(long estimateId, String packageCode) {
        update("UPDATE estimates SET applied_package_code = ? WHERE id = ?", packageCode, estimateId);
    }

    /** Insert a legacy offer: no snapshot, no name, fixed totals. Returns its id. */
    public long insertLegacyOffer(long projectId, long estimateId, String status) {
        return insert("INSERT INTO offers (project_id, estimate_id, revision, status, total_net, total_vat, "
                + "total_gross, inactive, created_date, created_by) "
                + "VALUES (?, ?, 1, ?, 100, 8, 108, false, NOW(), 'qa')", projectId, estimateId, status);
    }

    /** Insert an offer whose snapshot is a version 1 document (before Round 1). Returns its id. */
    public long insertV1Offer(long projectId, long estimateId, String status) {
        String v1 = "{\"version\":1,\"capturedAt\":\"2026-01-01T00:00:00Z\",\"vatRatePct\":8,"
                + "\"totalNet\":100,\"totalVat\":8,\"totalGross\":108,\"rooms\":[],\"groups\":[]}";
        return insert("INSERT INTO offers (project_id, estimate_id, revision, status, total_net, total_vat, "
                + "total_gross, inactive, snapshot, name, created_date, created_by) "
                + "VALUES (?, ?, 1, ?, 100, 8, 108, false, CAST(? AS jsonb), 'qa-v1', NOW(), 'qa')",
                projectId, estimateId, status, v1);
    }

    /**
     * Insert an offer with a version 1 snapshot (stored before the build-time room filter) that holds
     * one row with a room cell and one row without any cell, to check the read-time guard. Returns its id.
     */
    public long insertV1OfferWithEmptyRow(long projectId, long estimateId, String status, long roomId,
                                          String roomLabel, long categoryId, long workWithCell, long workWithoutCell) {
        String row = "{\"workItemId\":%d,\"nameRu\":\"%s\",\"namePl\":\"%s\",\"unitCode\":\"M2\","
                + "\"unitNameRu\":\"m2\",\"unitNamePl\":\"m2\",\"quantity\":%s,\"unitPrice\":10,\"valueNet\":%s,\"cells\":[%s]}";
        String withCell = String.format(row, workWithCell, "qa-v1-cell", "qa-v1-cell", "2", "20",
                "{\"roomId\":" + roomId + ",\"quantity\":2}");
        String withoutCell = String.format(row, workWithoutCell, "qa-v1-empty", "qa-v1-empty", "0", "0", "");
        String v1 = "{\"version\":1,\"capturedAt\":\"2026-01-01T00:00:00Z\",\"vatRatePct\":8,"
                + "\"totalNet\":20,\"totalVat\":1.6,\"totalGross\":21.6,"
                + "\"rooms\":[{\"id\":" + roomId + ",\"label\":\"" + roomLabel + "\"}],"
                + "\"groups\":[{\"categoryId\":" + categoryId + ",\"nameRu\":\"qa\",\"namePl\":\"qa\",\"rows\":["
                + withCell + "," + withoutCell + "]}]}";
        return insert("INSERT INTO offers (project_id, estimate_id, revision, status, total_net, total_vat, "
                + "total_gross, inactive, snapshot, name, created_date, created_by) "
                + "VALUES (?, ?, 1, ?, 20, 1.6, 21.6, false, CAST(? AS jsonb), 'qa-v1-rows', NOW(), 'qa')",
                projectId, estimateId, status, v1);
    }

    // ---- reads ----

    /** The first seeded offer package code (ordered by id). */
    public String firstPackageCode() {
        return (String) scalar("SELECT code FROM offer_packages WHERE active = true ORDER BY order_no, id LIMIT 1");
    }

    /** The second seeded offer package code (ordered by id). */
    public String secondPackageCode() {
        return (String) scalar("SELECT code FROM offer_packages WHERE active = true ORDER BY order_no, id OFFSET 1 LIMIT 1");
    }

    /** The package code currently applied to the estimate, or null. */
    public String appliedPackage(long estimateId) {
        return (String) scalar("SELECT applied_package_code FROM estimates WHERE id = ?", estimateId);
    }

    /** The quantity of a work in a room of the estimate, or null when there is no such row. */
    public BigDecimal roomQuantity(long estimateId, long workItemId, long roomId) {
        return (BigDecimal) scalar("SELECT q.quantity FROM estimate_line_room_qty q "
                + "JOIN estimate_lines l ON l.id = q.line_id "
                + "WHERE l.estimate_id = ? AND l.work_item_id = ? AND q.room_id = ?", estimateId, workItemId, roomId);
    }

    /** Number of estimate lines of a work in the estimate. */
    public int lineCount(long estimateId, long workItemId) {
        return asInt(scalar("SELECT COUNT(*) FROM estimate_lines WHERE estimate_id = ? AND work_item_id = ?",
                estimateId, workItemId));
    }

    /** Number of offers of a project. */
    public int offerCount(long projectId) {
        return asInt(scalar("SELECT COUNT(*) FROM offers WHERE project_id = ?", projectId));
    }

    /** Number of APPROVED offers of a project. */
    public int approvedCount(long projectId) {
        return asInt(scalar("SELECT COUNT(*) FROM offers WHERE project_id = ? AND status = 'APPROVED'", projectId));
    }

    /** Number of audit rows with the given operation for an offer. */
    public int auditCount(long offerId, String operation) {
        return asInt(scalar("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND operation = ? "
                + "AND entity_class LIKE '%OfferEntity'", offerId, operation));
    }

    /** Type, nullability and default of a column, joined with a bar, or null when it is missing. */
    public String columnFacts(String table, String column) {
        List<Object[]> r = rows("SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                + "WHERE table_name = ? AND column_name = ?", table, column);
        if (r.isEmpty()) {
            return null;
        }
        return r.get(0)[0] + "|" + r.get(0)[1] + "|" + r.get(0)[2] + "|" + r.size();
    }

    /** Number of indexes with the given name. */
    public int indexCount(String indexName) {
        return asInt(scalar("SELECT COUNT(*) FROM pg_indexes WHERE indexname = ?", indexName));
    }

    /** Latest OTP code for an email that is still usable. */
    public String latestOtp(String email) {
        return new OtpFixture(dataSource).latestCode(email);
    }

    /** Up to n seeded work items with a unit, one per work category, as {workItemId, unitId, categoryId}. */
    public List<long[]> workItemsInDistinctCategories(int n) {
        List<Object[]> r = rows("SELECT id, unit_id, work_category_id FROM (SELECT DISTINCT ON (work_category_id) id, unit_id, work_category_id "
                + "FROM work_items WHERE work_category_id IS NOT NULL AND unit_id IS NOT NULL "
                + "ORDER BY work_category_id, id) x ORDER BY work_category_id LIMIT ?", n);
        if (r.size() < n) {
            throw new IllegalStateException("Need " + n + " work categories, found " + r.size());
        }
        List<long[]> out = new ArrayList<>();
        for (Object[] row : r) {
            out.add(new long[] {((Number) row[0]).longValue(), ((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        return out;
    }

    /** The comparison-screen group key of a work category: its code, or its id when it has none. */
    public String categoryKey(long categoryId) {
        Object code = scalar("SELECT code FROM work_categories WHERE id = ?", categoryId);
        return code == null || code.toString().isBlank() ? String.valueOf(categoryId) : code.toString();
    }

    /** The localized name of an offer package: lang is ru or pl. */
    public String packageName(String code, String lang) {
        String column = "ru".equalsIgnoreCase(lang) ? "name_ru" : "name_pl";
        Object name = scalar("SELECT " + column + " FROM offer_packages WHERE code = ?", code);
        if (name == null) {
            throw new IllegalStateException("No offer package " + code);
        }
        return name.toString();
    }

    // ---- JDBC helpers ----

    private void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private long insert(String sql, Object... params) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("insert failed: " + e.getMessage(), e);
        }
    }

    private int update(String sql, Object... params) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("update failed: " + e.getMessage(), e);
        }
    }

    private List<Object[]> rows(String sql, Object... params) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            List<Object[]> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Object[] row = new Object[n];
                    for (int i = 0; i < n; i++) {
                        row[i] = rs.getObject(i + 1);
                    }
                    out.add(row);
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + e.getMessage(), e);
        }
    }

    private Object scalar(String sql, Object... params) {
        List<Object[]> r = rows(sql, params);
        return r.isEmpty() ? null : r.get(0)[0];
    }

    private static int asInt(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
