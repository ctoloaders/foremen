package com.foremen.controller.model.companyschedule;

import java.util.List;

/**
 * The cross-project staffing projection payload returned by {@code GET /api/company-schedule}
 * (FOR-07-01-cross-project-schedule-core, Requirement 1, 3, 10). It is the {@code Staffing_Projection}:
 * a member-indexed, date-ranged aggregation over the caller's {@code Accessible_Projects} within the
 * supplied filters, carrying one {@link MemberRow} per contributing {@code User} plus the project
 * {@link #legend()}.
 *
 * <p>Both lists are empty for an empty projection — a caller with no {@code Accessible_Projects}, a
 * non-ADMIN with an empty allowed-project set, or a result emptied by filters (Requirement 6.3,
 * 10.1). Rows, the bars within each row, and legend entries are all in the fixed deterministic order
 * of Requirement 10.4, so identical inputs yield an identical response.
 *
 * <p><strong>No cost-bearing field (Requirement 8).</strong> No {@link MemberRow},
 * {@link ProjectedBar}, or {@link ProjectLegendEntry} carries any money figure, man-days value,
 * rate, or worker pay-tier field — the projection is a pure time/assignment view.
 *
 * @param rows   one {@link MemberRow} per contributing user, deterministically ordered; empty for an
 *               empty projection
 * @param legend one {@link ProjectLegendEntry} per project appearing in the projection,
 *               deterministically ordered; empty for an empty projection
 */
public record CompanyScheduleView(
        List<MemberRow> rows,
        List<ProjectLegendEntry> legend
) {}
