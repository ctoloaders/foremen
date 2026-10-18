package com.foremen.qa.fixtures;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import javax.sql.DataSource;

/**
 * Direct-DB domain fixture for the FOR-06-01 work-reporting API slice (FOR-QA-AUTO-06-01).
 *
 * <p>The derivation / fast-path / revenue-freeze API cases need a reportable project: an estimate
 * with a priced {@code EstimateLine} (frozen {@code unit_price}) for a work item, per-room
 * {@code EstimateLineRoomQty} plan quantities for the PERCENT cases (plus one room with NO plan for
 * the no-plan reject), and the authoring worker's project-member work categories covering the work
 * item's category (Covering_Work, FOR-05-10b). The suite's existing estimate build is a browser
 * flow (EstimateTab); for the API slice this fixture seeds exactly the rows those cases read, via
 * JDBC against the isolated test DB ({@link Db}), mirroring {@link TestUserFixture}.
 *
 * <p>Teardown is cheap and FK-safe: {@code estimates.project_id} is {@code ON DELETE CASCADE} from
 * {@code projects}, and {@code estimate_lines} / {@code estimate_line_room_qty} cascade from their
 * parents, so deleting the project (done by the step's registered API teardown) discards the whole
 * estimate. This fixture only needs to clean the work categories link it added and the
 * {@code estimates} row it created; both are idempotent no-ops if already gone. All SQL is
 * parameterized.
 */
public final class WorkReportFixture {

    private final DataSource dataSource;

    public WorkReportFixture() {
        this(Db.dataSource());
    }

    public WorkReportFixture(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** A seeded reportable estimate: the line id plus the rooms and their plan quantities. */
    public record SeededEstimate(long estimateId, long lineId, long workItemId, long unitId,
                                 BigDecimal unitPrice, long roomWithPlanAId, BigDecimal planA,
                                 long roomWithPlanBId, BigDecimal planB, long roomNoPlanId) {
    }

    /** The id and category of a work item usable for a reportable estimate line. */
    public record WorkItemRef(long workItemId, long unitId, long workCategoryId) {
    }

    /**
     * The first seeded work item that has both a work category and a measurement unit. The
     * reportable estimate line is built on this item; the authoring worker must cover its category.
     */
    public WorkItemRef firstWorkItemWithCategory() {
        String sql = "SELECT id, unit_id, work_category_id FROM work_items "
                + "WHERE work_category_id IS NOT NULL AND unit_id IS NOT NULL "
                + "ORDER BY id LIMIT 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("No seeded work item with a category and unit found");
            }
            return new WorkItemRef(rs.getLong(1), rs.getLong(2), rs.getLong(3));
        } catch (SQLException e) {
            throw new IllegalStateException("firstWorkItemWithCategory failed: " + e.getMessage(), e);
        }
    }

    /** The seeded PLN currency id (estimates require a currency). */
    public long plnCurrencyId() {
        String sql = "SELECT id FROM currencies WHERE code = 'PLN'";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("Seeded PLN currency not found");
            }
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("plnCurrencyId failed: " + e.getMessage(), e);
        }
    }

    /**
     * Seed a priced estimate for {@code projectId} on {@code workItem} with the given frozen
     * {@code unitPrice}: an {@code estimates} row (DRAFT, PLN), one {@code estimate_lines} row, and
     * {@code estimate_line_room_qty} plan rows for roomWithPlanA ({@code planA}) and roomWithPlanB
     * ({@code planB}). {@code roomNoPlanId} is left WITHOUT a plan row (the PERCENT-no-plan case).
     *
     * @return the seeded ids for the steps to report against
     */
    public SeededEstimate seedEstimate(long projectId, WorkItemRef workItem, BigDecimal unitPrice,
                                       long roomWithPlanAId, BigDecimal planA,
                                       long roomWithPlanBId, BigDecimal planB,
                                       long roomNoPlanId) {
        try (Connection c = dataSource.getConnection()) {
            long currencyId = plnCurrencyId();
            long estimateId = insertEstimate(c, projectId, currencyId);
            long lineId = insertEstimateLine(c, estimateId, workItem, unitPrice,
                    planA.add(planB));
            insertRoomQty(c, lineId, roomWithPlanAId, planA);
            insertRoomQty(c, lineId, roomWithPlanBId, planB);
            return new SeededEstimate(estimateId, lineId, workItem.workItemId(), workItem.unitId(),
                    unitPrice, roomWithPlanAId, planA, roomWithPlanBId, planB, roomNoPlanId);
        } catch (SQLException e) {
            throw new IllegalStateException("seedEstimate failed: " + e.getMessage(), e);
        }
    }

    /**
     * Link a project member (by {@code project_members.id}) to a work category, so the worker's
     * project-member work categories cover a work item of that category (Covering_Work, R3.2).
     * Idempotent via a NOT EXISTS guard.
     */
    public void linkMemberWorkCategory(long projectMemberId, long workCategoryId) {
        String sql = "INSERT INTO project_member_work_categories "
                + "(project_member_id, work_category_id, order_no) "
                + "SELECT ?, ?, 0 WHERE NOT EXISTS ("
                + "  SELECT 1 FROM project_member_work_categories "
                + "  WHERE project_member_id = ? AND work_category_id = ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, projectMemberId);
            ps.setLong(2, workCategoryId);
            ps.setLong(3, projectMemberId);
            ps.setLong(4, workCategoryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("linkMemberWorkCategory failed: " + e.getMessage(), e);
        }
    }

    /** Resolve a user's {@code project_members.id} for a project (needed to link work categories). */
    public long projectMemberId(long userId, long projectId) {
        String sql = "SELECT id FROM project_members WHERE user_id = ? AND project_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "No project_members row for user " + userId + " in project " + projectId);
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("projectMemberId failed: " + e.getMessage(), e);
        }
    }

    /** Delete the seeded estimate (cascades its lines + room-qty). Idempotent. */
    public void deleteEstimate(long estimateId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM estimates WHERE id = ?")) {
            ps.setLong(1, estimateId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("deleteEstimate failed: " + e.getMessage(), e);
        }
    }

    /** Hard-delete a work report by id (FK-safe: work_report_rooms cascade). Idempotent. */
    public void deleteWorkReport(long reportId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM work_reports WHERE id = ?")) {
            ps.setLong(1, reportId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("deleteWorkReport failed: " + e.getMessage(), e);
        }
    }

    // ---- internals ----

    private static long insertEstimate(Connection c, long projectId, long currencyId)
            throws SQLException {
        String sql = "INSERT INTO estimates "
                + "(project_id, currency_id, status, total_net, total_vat, total_gross, "
                + " created_date, created_by) "
                + "VALUES (?, ?, 'DRAFT', 0, 0, 0, NOW(), 'qa')";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, projectId);
            ps.setLong(2, currencyId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private static long insertEstimateLine(Connection c, long estimateId, WorkItemRef workItem,
                                           BigDecimal unitPrice, BigDecimal quantity)
            throws SQLException {
        BigDecimal valueNet = unitPrice.multiply(quantity);
        String sql = "INSERT INTO estimate_lines "
                + "(estimate_id, work_item_id, work_price_id, unit_id, line_no, comment, "
                + " unit_price, quantity, value_net, created_date, created_by) "
                + "VALUES (?, ?, NULL, ?, 1, NULL, ?, ?, ?, NOW(), 'qa')";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, estimateId);
            ps.setLong(2, workItem.workItemId());
            ps.setLong(3, workItem.unitId());
            ps.setBigDecimal(4, unitPrice);
            ps.setBigDecimal(5, quantity);
            ps.setBigDecimal(6, valueNet);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private static void insertRoomQty(Connection c, long lineId, long roomId, BigDecimal quantity)
            throws SQLException {
        String sql = "INSERT INTO estimate_line_room_qty "
                + "(line_id, room_id, quantity, volume_overridden, created_date, created_by) "
                + "VALUES (?, ?, ?, false, NOW(), 'qa')";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, lineId);
            ps.setLong(2, roomId);
            ps.setBigDecimal(3, quantity);
            ps.executeUpdate();
        }
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
                return keys.getLong(1);
            }
            throw new SQLException("INSERT did not return a generated id");
        }
    }
}
