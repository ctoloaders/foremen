package com.foremen.service.companyschedule;

import com.foremen.controller.model.companyschedule.BarKind;
import com.foremen.controller.model.companyschedule.CompanyScheduleQuery;
import com.foremen.controller.model.companyschedule.CompanyScheduleView;
import com.foremen.controller.model.companyschedule.ConflictPair;
import com.foremen.controller.model.companyschedule.MemberRow;
import com.foremen.controller.model.companyschedule.ProjectLegendEntry;
import com.foremen.controller.model.companyschedule.ProjectedBar;
import com.foremen.service.schedule.ScheduleCalculator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, side-effect-free aggregation helpers behind the cross-project staffing projection
 * (FOR-07-01; design §"Pure projector"). Every method is {@code static} and depends only on its
 * arguments — no database, no Spring context, no clock — so each rule is directly unit- and
 * property-testable, mirroring {@link ScheduleCalculator}.
 */
public final class CrossProjectScheduleProjector {

    private CrossProjectScheduleProjector() {
    }

    /** Role_Filter / {@code projectRole.code} value for a FOREMAN member. */
    private static final String FOREMAN_ROLE = "FOREMAN";

    // ------------------------------------------------------------------------------------------
    // Top-level orchestration (FOR-07-01 Requirements 1, 2, 7, 10; design §"Pure projector").
    //   Turns the raw Accessible_Project inputs plus the caller's filters into the immutable
    //   CompanyScheduleView: group worker bars by user into Member_Rows, synthesize foreman spans,
    //   apply foreman precedence, exclude Uncovered (null-member) bars upstream, apply the
    //   Role/Project/Member/Date_Window filters conjunctively, omit members empty after filtering,
    //   assign a deterministic per-project color + legend, detect per-member conflicts, and emit
    //   everything in the fixed deterministic order of Requirement 10.4. Carries NO cost-bearing
    //   field (Requirement 8).
    // ------------------------------------------------------------------------------------------

    /**
     * Builds the complete cross-project staffing projection (the {@code Staffing_Projection}) from
     * the caller's Accessible_Project inputs and the supplied filters. This is the pure, top-level
     * orchestration of the projector; it calls the sibling helpers ({@link ProjectColorAssigner},
     * {@link #workerBarDates}, {@link #foremanSpanDates}, {@link #detectConflicts}) and performs no
     * I/O.
     *
     * <p>Algorithm:
     * <ol>
     *   <li><strong>Foreman precedence (R1.3, R1.8).</strong> For each project, the user ids of its
     *       ACTIVE FOREMAN members are collected. A user who is a foreman of a project contributes
     *       only that project's Foreman_Span_Bar and none of that user's Worker_Category_Bars for the
     *       <em>same</em> project.</li>
     *   <li><strong>Uncovered exclusion (R2).</strong> Only {@link WorkerBarInput}s (non-null-member
     *       Real_Bars) are present in the input, so no Uncovered_Bar ever reaches the projection.</li>
     *   <li><strong>Grouping (R1.1, R1.5).</strong> Every bar is grouped by {@code userId}; each
     *       distinct contributing user becomes one {@link MemberRow} aggregating that user's bars
     *       across all Accessible_Projects.</li>
     *   <li><strong>Filters (R7).</strong> Role / Project / Member / Date_Window are applied
     *       conjunctively per bar; a {@code null} filter imposes no restriction. Any Date_Window
     *       bound drops every Undatable_Bar (R7.5).</li>
     *   <li><strong>Empty-member omission (R10.3).</strong> A user with no remaining bar after
     *       filtering yields no row; a row is never emitted with no user (R2.3).</li>
     *   <li><strong>Color + legend (R3).</strong> {@link ProjectColorAssigner#assignColors} assigns a
     *       deterministic palette index per project id appearing in the (post-filter) projection,
     *       shared by every bar of that project and its legend entry.</li>
     *   <li><strong>Conflicts (R5).</strong> {@link #detectConflicts} records per-member overlap of
     *       datable Worker_Category_Bars only.</li>
     *   <li><strong>Determinism (R10.4, R11.2).</strong> Rows are ordered by {@code (userName,
     *       userId)}, a row's bars by {@code (startDate NULLS LAST, projectId, workCategoryId,
     *       startHalfDays)}, and the legend by {@code projectId}.</li>
     * </ol>
     *
     * @param projects      the caller's Accessible_Project inputs (already scope-resolved and
     *                      Project_Filter-intersected by the service); {@code null}/empty ⇒ empty view
     * @param filters       the normalized server-side filters; {@code null} ⇒ no filters
     * @param russianLocale {@code true} to localize a worker bar's category name to Russian,
     *                      {@code false} for Polish
     * @return the immutable {@link CompanyScheduleView}; empty {@code rows} and {@code legend} when
     *         nothing survives
     */
    public static CompanyScheduleView project(List<ProjectInput> projects,
                                              CompanyScheduleQuery filters,
                                              boolean russianLocale) {
        List<String> roleFilter = filters == null ? null : filters.roles();
        List<Long> projectFilter = filters == null ? null : filters.projectIds();
        List<Long> memberFilter = filters == null ? null : filters.memberIds();
        LocalDate from = filters == null ? null : filters.from();
        LocalDate to = filters == null ? null : filters.to();
        boolean dateWindowSupplied = from != null || to != null;

        Set<String> roleSet = roleFilter == null ? null : new HashSet<>(roleFilter);
        Set<Long> projectSet = projectFilter == null ? null : new HashSet<>(projectFilter);
        Set<Long> memberSet = memberFilter == null ? null : new HashSet<>(memberFilter);

        // Grouped, filtered bars per user, preserving the user's role for the row's Company_Role.
        Map<Long, UserAccumulator> byUser = new LinkedHashMap<>();

        if (projects != null) {
            for (ProjectInput project : projects) {
                if (project == null) {
                    continue;
                }
                // Project_Filter (R7.3): skip whole project when a filter is supplied and excludes it.
                if (projectSet != null && !projectSet.contains(project.projectId())) {
                    continue;
                }

                // Foreman user ids of this project (R1.8 foreman precedence).
                Set<Long> foremanUserIds = new HashSet<>();
                if (project.foremen() != null) {
                    for (ForemanInput foreman : project.foremen()) {
                        if (foreman != null) {
                            foremanUserIds.add(foreman.userId());
                        }
                    }
                }

                // Foreman_Span_Bars.
                if (project.foremen() != null) {
                    for (ForemanInput foreman : project.foremen()) {
                        if (foreman == null) {
                            continue;
                        }
                        LocalDate[] span = foremanSpanDates(project.startDate(), project.endDate());
                        ProjectedBar bar = new ProjectedBar(
                                BarKind.FOREMAN_SPAN,
                                project.projectId(),
                                project.projectName(),
                                0, // colorIndex assigned after the post-filter project set is known
                                null, null, null, null,
                                span[0], span[1],
                                span[0] == null || span[1] == null);
                        if (includeBar(bar, FOREMAN_ROLE, foreman.userId(),
                                roleSet, memberSet, from, to, dateWindowSupplied)) {
                            accumulate(byUser, foreman.userId(), foreman.userName(), FOREMAN_ROLE, bar);
                        }
                    }
                }

                // Worker_Category_Bars (drop a foreman-also-worker user's worker bars for THIS project).
                if (project.workerBars() != null) {
                    for (WorkerBarInput worker : project.workerBars()) {
                        if (worker == null) {
                            continue;
                        }
                        if (foremanUserIds.contains(worker.userId())) {
                            continue; // foreman precedence (R1.8)
                        }
                        LocalDate[] dates = workerBarDates(
                                project.startDate(), worker.startHalfDays(), worker.durationHalfDays());
                        String categoryName = russianLocale
                                ? worker.categoryNameRu()
                                : worker.categoryNamePl();
                        ProjectedBar bar = new ProjectedBar(
                                BarKind.WORKER_CATEGORY,
                                project.projectId(),
                                project.projectName(),
                                0, // colorIndex assigned after the post-filter project set is known
                                worker.workCategoryId(),
                                categoryName,
                                worker.startHalfDays(),
                                worker.durationHalfDays(),
                                dates[0], dates[1],
                                dates[0] == null || dates[1] == null);
                        if (includeBar(bar, worker.roleCode(), worker.userId(),
                                roleSet, memberSet, from, to, dateWindowSupplied)) {
                            accumulate(byUser, worker.userId(), worker.userName(), worker.roleCode(), bar);
                        }
                    }
                }
            }
        }

        // Deterministic per-project color over the project ids actually surviving into the projection.
        List<Long> survivingProjectIds = new ArrayList<>();
        for (UserAccumulator acc : byUser.values()) {
            for (ProjectedBar bar : acc.bars) {
                survivingProjectIds.add(bar.projectId());
            }
        }
        Map<Long, Integer> colors = ProjectColorAssigner.assignColors(survivingProjectIds);

        // Rows ordered by (userName, userId); omit empty-after-filter members (R10.3, R2.3).
        List<UserAccumulator> orderedUsers = new ArrayList<>(byUser.values());
        orderedUsers.sort(Comparator
                .comparing((UserAccumulator a) -> a.userName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(a -> a.userId));

        List<MemberRow> rows = new ArrayList<>(orderedUsers.size());
        for (UserAccumulator acc : orderedUsers) {
            if (acc.bars.isEmpty()) {
                continue;
            }
            // Bars ordered by (startDate NULLS LAST, projectId, workCategoryId NULLS LAST, startHalfDays NULLS LAST).
            acc.bars.sort(Comparator
                    .comparing(ProjectedBar::startDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ProjectedBar::projectId, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ProjectedBar::workCategoryId, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ProjectedBar::startHalfDays, Comparator.nullsLast(Comparator.naturalOrder())));

            // Apply the final shared color index to every bar now that ordering is fixed.
            List<ProjectedBar> coloredBars = new ArrayList<>(acc.bars.size());
            for (ProjectedBar bar : acc.bars) {
                int colorIndex = colors.getOrDefault(bar.projectId(), 0);
                coloredBars.add(new ProjectedBar(
                        bar.kind(), bar.projectId(), bar.projectName(), colorIndex,
                        bar.workCategoryId(), bar.workCategoryName(),
                        bar.startHalfDays(), bar.durationHalfDays(),
                        bar.startDate(), bar.endDate(), bar.unanchored()));
            }

            List<ConflictPair> conflicts = detectConflicts(coloredBars);
            rows.add(new MemberRow(acc.userId, acc.userName, acc.companyRole, coloredBars, conflicts));
        }

        // Legend ordered by projectId (the color map is already ascending-id ordered).
        List<ProjectLegendEntry> legend = new ArrayList<>(colors.size());
        Map<Long, String> projectNames = new LinkedHashMap<>();
        for (UserAccumulator acc : byUser.values()) {
            for (ProjectedBar bar : acc.bars) {
                projectNames.putIfAbsent(bar.projectId(), bar.projectName());
            }
        }
        for (Map.Entry<Long, Integer> entry : colors.entrySet()) {
            legend.add(new ProjectLegendEntry(
                    entry.getKey(), projectNames.get(entry.getKey()), entry.getValue()));
        }

        return new CompanyScheduleView(rows, legend);
    }

    /**
     * Whether a bar passes the conjunctive Role / Member / Date_Window filters (Project_Filter is
     * applied earlier, per project). A {@code null} filter set imposes no restriction (R7.2, 7.4).
     * Any supplied Date_Window bound drops every Undatable_Bar and keeps a datable bar only when its
     * inclusive interval intersects {@code [from, to]} (R7.5, 7.6).
     */
    private static boolean includeBar(ProjectedBar bar, String roleCode, long userId,
                                      Set<String> roleSet, Set<Long> memberSet,
                                      LocalDate from, LocalDate to, boolean dateWindowSupplied) {
        if (roleSet != null && (roleCode == null || !roleSet.contains(roleCode))) {
            return false;
        }
        if (memberSet != null && !memberSet.contains(userId)) {
            return false;
        }
        if (dateWindowSupplied) {
            if (bar.startDate() == null || bar.endDate() == null) {
                return false; // Undatable_Bar excluded once any Date_Window bound is supplied (R7.5)
            }
            // Intersect [bar.start, bar.end] with [from, to] (either bound may be open).
            if (to != null && bar.startDate().isAfter(to)) {
                return false;
            }
            if (from != null && bar.endDate().isBefore(from)) {
                return false;
            }
        }
        return true;
    }

    /** Add a bar to its user's accumulator, creating the row and fixing its Company_Role on first bar. */
    private static void accumulate(Map<Long, UserAccumulator> byUser, long userId, String userName,
                                   String roleCode, ProjectedBar bar) {
        UserAccumulator acc = byUser.computeIfAbsent(
                userId, id -> new UserAccumulator(id, userName, roleCode));
        // Foreman precedence for the row's Company_Role: a user contributing any foreman span reads
        // as FOREMAN regardless of accumulation order, keeping the role deterministic (R1.4).
        if (FOREMAN_ROLE.equals(roleCode)) {
            acc.companyRole = FOREMAN_ROLE;
        }
        acc.bars.add(bar);
    }

    /** Mutable per-user grouping scratch used only inside {@link #project}. */
    private static final class UserAccumulator {
        private final long userId;
        private final String userName;
        private String companyRole;
        private final List<ProjectedBar> bars = new ArrayList<>();

        private UserAccumulator(long userId, String userName, String companyRole) {
            this.userId = userId;
            this.userName = userName;
            this.companyRole = companyRole;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Absolute-date projection (FOR-07-01 Requirements 4.1, 4.2, 4.3, 4.4; design §"Absolute dates")
    //   Reuses ScheduleCalculator.calendarDayOfHalfDay so a Worker_Category_Bar projects onto the
    //   same calendar days the per-project Gantt draws. Unanchored worker bars and partial-date
    //   foreman spans are Undatable_Bars: both dates null.
    // ------------------------------------------------------------------------------------------

    /**
     * The absolute {@code [startDate, endDate]} of a Worker_Category_Bar relative to its project's
     * Schedule_Anchor (Requirements 4.1, 4.3). The bar's half-day fields are turned into whole
     * calendar days with {@link ScheduleCalculator#calendarDayOfHalfDay(int)} (the same ceiling the
     * per-project schedule uses), then offset from the anchor where day 1 is the anchor itself:
     *
     * <ul>
     *   <li>{@code startDate = anchor.plusDays(calendarDayOfHalfDay(startHalfDays) - 1)}</li>
     *   <li>{@code endDate   = anchor.plusDays(calendarDayOfHalfDay(startHalfDays + durationHalfDays - 1) - 1)}</li>
     * </ul>
     *
     * <p>When the project is an Unanchored_Project ({@code anchor == null}) the bar is an
     * Undatable_Bar: both returned dates are {@code null} (Requirement 4.3).
     *
     * @param anchor           the project's Schedule_Anchor ({@code Project.startDate}); {@code null}
     *                         for an Unanchored_Project
     * @param startHalfDays    the bar's 1-based half-day start offset ({@code >= 1})
     * @param durationHalfDays the bar's duration in half-days ({@code >= 1})
     * @return a two-element array {@code [startDate, endDate]}; both elements {@code null} when
     *         {@code anchor} is {@code null}
     */
    public static LocalDate[] workerBarDates(LocalDate anchor, int startHalfDays, int durationHalfDays) {
        if (anchor == null) {
            return new LocalDate[] {null, null};
        }
        int startDay = ScheduleCalculator.calendarDayOfHalfDay(startHalfDays);
        int finishDay = ScheduleCalculator.calendarDayOfHalfDay(
                ScheduleCalculator.finishHalfDay(startHalfDays, durationHalfDays));
        LocalDate startDate = anchor.plusDays(startDay - 1L);
        LocalDate endDate = anchor.plusDays(finishDay - 1L);
        return new LocalDate[] {startDate, endDate};
    }

    /**
     * The absolute {@code [startDate, endDate]} of a Foreman_Span_Bar (Requirements 4.2, 4.4): a
     * foreman span is datable <strong>only when both</strong> the project's start and end dates are
     * present, in which case it spans exactly {@code [start, end]}. If either date is missing the
     * span is an Undatable_Bar and both returned dates are {@code null}.
     *
     * @param start the project's start date; {@code null} makes the span undatable
     * @param end   the project's end date; {@code null} makes the span undatable
     * @return a two-element array {@code [start, end]} when both are present; otherwise both
     *         elements {@code null}
     */
    public static LocalDate[] foremanSpanDates(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return new LocalDate[] {null, null};
        }
        return new LocalDate[] {start, end};
    }

    // ------------------------------------------------------------------------------------------
    // Overlap / conflict detection (FOR-07-01 Requirements 5.1-5.6; design §"Conflict detection")
    //   Per member, over that member's datable Worker_Category_Bars only: two bars conflict exactly
    //   when their inclusive closed intervals overlap. Foreman spans and undatable bars never form a
    //   conflict, and a bar never conflicts with itself.
    // ------------------------------------------------------------------------------------------

    /**
     * Whether two inclusive closed date intervals {@code [aStart, aEnd]} and {@code [bStart, bEnd]}
     * overlap (Requirement 5.2). Using the standard closed-interval rule, they overlap exactly when
     * {@code aStart <= bEnd && bStart <= aEnd} (expressed with {@link LocalDate#isAfter} so the
     * comparison is inclusive of shared endpoints).
     *
     * <p>If <strong>any</strong> of the four bounds is {@code null} the corresponding bar is an
     * {@code Undatable_Bar} with no absolute interval to compare, so this method returns
     * {@code false} (Requirement 5.4).
     *
     * @param aStart the first interval's inclusive start; {@code null} = undatable
     * @param aEnd   the first interval's inclusive end; {@code null} = undatable
     * @param bStart the second interval's inclusive start; {@code null} = undatable
     * @param bEnd   the second interval's inclusive end; {@code null} = undatable
     * @return {@code true} when both intervals are fully datable and their closed intervals overlap;
     *         {@code false} otherwise (including whenever any bound is {@code null})
     */
    public static boolean intervalsOverlap(LocalDate aStart, LocalDate aEnd,
                                           LocalDate bStart, LocalDate bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) {
            return false;
        }
        // aStart <= bEnd && bStart <= aEnd
        return !aStart.isAfter(bEnd) && !bStart.isAfter(aEnd);
    }

    /**
     * The overlap {@code Conflict}s of a single member's bar list (Requirements 5.1, 5.3, 5.5, 5.6).
     * The supplied {@code bars} are one {@link com.foremen.controller.model.companyschedule.MemberRow MemberRow}'s
     * {@code Projected_Bars} (scoped to one {@code User}, so no conflict ever spans two users —
     * Requirement 5.6). Each distinct pair of <strong>datable</strong>
     * {@link BarKind#WORKER_CATEGORY} bars whose inclusive intervals overlap (per
     * {@link #intervalsOverlap}) is recorded as a {@link ConflictPair}:
     *
     * <ul>
     *   <li>{@link BarKind#FOREMAN_SPAN} bars are skipped entirely — a foreman legitimately runs
     *       several overlapping projects (Requirement 5.5).</li>
     *   <li>{@code Undatable_Bars} (null {@code startDate}/{@code endDate}) are skipped — they have
     *       no interval to compare (Requirement 5.4).</li>
     *   <li>A bar is never paired with itself (Requirement 5.3).</li>
     * </ul>
     *
     * <p>The returned pairs hold zero-based indices into the supplied {@code bars} list with
     * {@code barIndexA < barIndexB}, emitted in ascending {@code (barIndexA, barIndexB)} order, so
     * the result is a deterministic function of the input (Requirement 10.4).
     *
     * @param bars one member's ordered {@code Projected_Bars}
     * @return the member's conflict pairs, deterministically ordered; empty when there is no overlap
     */
    public static List<ConflictPair> detectConflicts(List<ProjectedBar> bars) {
        List<ConflictPair> conflicts = new ArrayList<>();
        if (bars == null) {
            return conflicts;
        }
        for (int i = 0; i < bars.size(); i++) {
            ProjectedBar a = bars.get(i);
            if (!isDatableWorkerBar(a)) {
                continue;
            }
            for (int j = i + 1; j < bars.size(); j++) {
                ProjectedBar b = bars.get(j);
                if (!isDatableWorkerBar(b)) {
                    continue;
                }
                if (intervalsOverlap(a.startDate(), a.endDate(), b.startDate(), b.endDate())) {
                    conflicts.add(new ConflictPair(i, j));
                }
            }
        }
        return conflicts;
    }

    /**
     * Whether a bar is eligible for conflict detection: a datable {@link BarKind#WORKER_CATEGORY}
     * bar (non-null kind, worker category, and both absolute dates present). Foreman spans and
     * undatable bars are not eligible (Requirements 5.4, 5.5).
     */
    private static boolean isDatableWorkerBar(ProjectedBar bar) {
        return bar != null
                && bar.kind() == BarKind.WORKER_CATEGORY
                && bar.startDate() != null
                && bar.endDate() != null;
    }
}
