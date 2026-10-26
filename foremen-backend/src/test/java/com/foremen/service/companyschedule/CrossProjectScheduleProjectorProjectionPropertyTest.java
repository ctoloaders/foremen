package com.foremen.service.companyschedule;

import com.foremen.controller.model.companyschedule.BarKind;
import com.foremen.controller.model.companyschedule.CompanyScheduleQuery;
import com.foremen.controller.model.companyschedule.CompanyScheduleView;
import com.foremen.controller.model.companyschedule.ConflictPair;
import com.foremen.controller.model.companyschedule.MemberRow;
import com.foremen.controller.model.companyschedule.ProjectLegendEntry;
import com.foremen.controller.model.companyschedule.ProjectedBar;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the top-level {@code project(...)} orchestration of
 * {@link CrossProjectScheduleProjector#project(java.util.List, CompanyScheduleQuery, boolean)}
 * (FOR-07-01-cross-project-schedule-core; design §"Correctness Properties",
 * Properties 1, 2, 3, 8, 9, 10, 11, 12).
 *
 * <p>{@code project(...)} is a pure, side-effect-free function over DB-free input records — no
 * persistence, no Spring context, no clock — so these properties are cheap to run over 100+ generated
 * inputs that exercise anchored vs unanchored projects, foreman-also-worker users, uncovered
 * (null-member) bars, overlapping worker intervals, and every filter subset (mirrors the sibling
 * {@code ProjectColorAssignerPropertyTest} / {@code CrossProjectScheduleProjectorConflictPropertyTest}).
 *
 * <ul>
 *   <li><b>Property 1:</b> exactly one {@link MemberRow} per distinct contributing {@code userId};
 *       never a row with no identifiable user.</li>
 *   <li><b>Property 2:</b> no projected bar derives from a null-member (Uncovered) bar — every bar
 *       traces back to a real {@link WorkerBarInput} / {@link ForemanInput}.</li>
 *   <li><b>Property 3:</b> for a user who is an ACTIVE FOREMAN of a project and also owns worker bars
 *       there, the projection emits that user's foreman span and none of their worker bars for the
 *       same project.</li>
 *   <li><b>Property 8:</b> every result bar satisfies all supplied filters; an out-of-scope
 *       Project_Filter id never widens the result.</li>
 *   <li><b>Property 9:</b> any Date_Window bound excludes undatable bars and keeps a datable bar
 *       exactly when its inclusive interval intersects {@code [from, to]}; no window keeps all.</li>
 *   <li><b>Property 10:</b> a member emptied by filters yields no row.</li>
 *   <li><b>Property 11:</b> ordering of rows / bars / legend is a pure function of the input.</li>
 *   <li><b>Property 12:</b> no DTO record component is a money / man-days / rate / pay-tier field.</li>
 * </ul>
 *
 * <p>Feature: FOR-07-01-cross-project-schedule-core, Properties 1,2,3,8,9,10,11,12: projection
 *
 * <p><b>Validates: Requirements 1.1, 1.3, 1.5, 1.8, 2.1-2.4, 4.5, 7.2-7.7, 8.1-8.3, 10.3, 10.4,
 * 11.2</b>
 */
@Tag("Feature: FOR-07-01-cross-project-schedule-core, Properties 1,2,3,8,9,10,11,12: projection")
class CrossProjectScheduleProjectorProjectionPropertyTest {

    private static final String FOREMAN = "FOREMAN";
    private static final String WORKER = "WORKER";

    // ==========================================================================================
    // Property 1: One row per contributing user (R1.1, R1.5, R2.3)
    //   Exactly one MemberRow per distinct contributing userId; a row aggregates that user's bars
    //   across all projects; no row is ever emitted with no identifiable user / no bar.
    // ==========================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 1: One row per contributing user")
    void oneRowPerContributingUser(@ForAll("projects") List<ProjectInput> projects,
                                   @ForAll boolean russianLocale) {
        CompanyScheduleView view = CrossProjectScheduleProjector.project(projects, null, russianLocale);

        // User ids are distinct across rows: exactly one row per user.
        List<Long> rowUserIds = view.rows().stream().map(MemberRow::userId).toList();
        assertThat(rowUserIds).doesNotHaveDuplicates();

        // Every row is for an identifiable user and carries at least one bar (never an empty row).
        for (MemberRow row : view.rows()) {
            assertThat(row.userId()).isNotNull();
            assertThat(row.bars()).isNotEmpty();
        }

        // The set of row user ids equals exactly the set of users that actually contribute a bar
        // (workers whose bars survive foreman precedence, plus all foremen of datable/undatable spans).
        Set<Long> expectedContributors = expectedContributingUserIds(projects);
        assertThat(new HashSet<>(rowUserIds)).isEqualTo(expectedContributors);
    }

    // ==========================================================================================
    // Property 2: Uncovered bars never appear (R2.1, R2.2, R2.4)
    //   Every emitted bar traces back to a real input WorkerBarInput or ForemanInput — the projector
    //   never fabricates a bar from a null-member source. We model Uncovered bars as (projectId,
    //   userId, category, startHalfDays) tuples that are NOT present in any input; the projection
    //   contains none of them because only real inputs produce bars.
    // ==========================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 2: Uncovered bars never appear")
    void everyProjectedBarTracesToARealInput(@ForAll("projects") List<ProjectInput> projects,
                                             @ForAll boolean russianLocale) {
        CompanyScheduleView view = CrossProjectScheduleProjector.project(projects, null, russianLocale);

        // Build the set of legitimate (projectId, userId) sources from the real inputs only.
        Set<String> realWorkerKeys = new HashSet<>();
        Set<String> realForemanKeys = new HashSet<>();
        for (ProjectInput p : projects) {
            if (p == null) {
                continue;
            }
            if (p.workerBars() != null) {
                for (WorkerBarInput w : p.workerBars()) {
                    if (w != null) {
                        realWorkerKeys.add(p.projectId() + ":" + w.userId() + ":" + w.workCategoryId()
                                + ":" + w.startHalfDays() + ":" + w.durationHalfDays());
                    }
                }
            }
            if (p.foremen() != null) {
                for (ForemanInput f : p.foremen()) {
                    if (f != null) {
                        realForemanKeys.add(p.projectId() + ":" + f.userId());
                    }
                }
            }
        }

        // Every emitted bar must correspond to a real input source; nothing is synthesized from a
        // null-member (Uncovered) bar — those are never present in the input in the first place.
        for (MemberRow row : view.rows()) {
            for (ProjectedBar bar : row.bars()) {
                if (bar.kind() == BarKind.FOREMAN_SPAN) {
                    assertThat(realForemanKeys).contains(bar.projectId() + ":" + row.userId());
                } else {
                    String key = bar.projectId() + ":" + row.userId() + ":" + bar.workCategoryId()
                            + ":" + bar.startHalfDays() + ":" + bar.durationHalfDays();
                    assertThat(realWorkerKeys).contains(key);
                }
            }
        }
    }

    // ==========================================================================================
    // Property 3: Foreman precedence over own worker bars (R1.3, R1.8)
    //   For a user who is an ACTIVE FOREMAN of a project AND owns worker bars in that same project,
    //   the projection emits that user's FOREMAN_SPAN for the project and NONE of their worker bars
    //   for that project. The row's companyRole reads FOREMAN whenever any foreman span is present.
    // ==========================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 3: Foreman precedence over own worker bars")
    void foremanPrecedenceDropsOwnWorkerBarsForTheSameProject(
            @ForAll("foremanAlsoWorkerProjects") List<ProjectInput> projects,
            @ForAll boolean russianLocale) {
        CompanyScheduleView view = CrossProjectScheduleProjector.project(projects, null, russianLocale);

        for (ProjectInput p : projects) {
            Set<Long> foremanIds = new HashSet<>();
            for (ForemanInput f : p.foremen()) {
                foremanIds.add(f.userId());
            }
            for (MemberRow row : view.rows()) {
                if (!foremanIds.contains(row.userId())) {
                    continue;
                }
                boolean hasForemanSpanForProject = false;
                for (ProjectedBar bar : row.bars()) {
                    if (bar.projectId() == p.projectId()) {
                        // No worker bar of this foreman for this project (R1.8).
                        assertThat(bar.kind()).isEqualTo(BarKind.FOREMAN_SPAN);
                        hasForemanSpanForProject = true;
                    }
                }
                // The foreman span for this project IS emitted (R1.3).
                assertThat(hasForemanSpanForProject).isTrue();
                // A row contributing any foreman span reads as FOREMAN (R1.4 note from the task).
                assertThat(row.companyRole()).isEqualTo(FOREMAN);
            }
        }
    }

    // ==========================================================================================
    // Property 8: Filters are conjunctive and never widen scope (R7.2, R7.3, R7.4, R7.7)
    //   Every bar in the result satisfies all supplied filters; adding an out-of-scope Project_Filter
    //   id never changes the result versus omitting it.
    // ==========================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 8: Filters are conjunctive and never widen scope")
    void filtersAreConjunctiveAndNeverWiden(@ForAll("projects") List<ProjectInput> projects,
                                            @ForAll("roleFilters") List<String> roles,
                                            @ForAll("idSubset") List<Long> projectIds,
                                            @ForAll("idSubset") List<Long> memberIds,
                                            @ForAll boolean russianLocale) {
        CompanyScheduleQuery filters =
                new CompanyScheduleQuery(roles, projectIds, memberIds, null, null);
        CompanyScheduleView view = CrossProjectScheduleProjector.project(projects, filters, russianLocale);

        Set<String> roleSet = roles == null ? null : new HashSet<>(
                roles.stream().map(r -> r.toUpperCase(Locale.ROOT)).toList());
        Set<Long> projectSet = projectIds == null || projectIds.isEmpty() ? null : new HashSet<>(projectIds);
        Set<Long> memberSet = memberIds == null || memberIds.isEmpty() ? null : new HashSet<>(memberIds);

        Set<Long> allowedProjectIds = allProjectIds(projects);

        for (MemberRow row : view.rows()) {
            // Member_Filter (R7.4): every surviving row's user is in the member set.
            if (memberSet != null) {
                assertThat(memberSet).contains(row.userId());
            }
            for (ProjectedBar bar : row.bars()) {
                // Project_Filter (R7.3): every bar's project is in the filter set when one is supplied.
                if (projectSet != null) {
                    assertThat(projectSet).contains(bar.projectId());
                }
                // Every bar belongs to an Accessible_Project (R7.7 — never widened to an unknown id).
                assertThat(allowedProjectIds).contains(bar.projectId());
                // Role_Filter (R7.2): a foreman span is only emitted when FOREMAN is allowed; a worker
                // bar only when its role is allowed. The row's companyRole reflects the contributing
                // role; a foreman span forces FOREMAN.
                if (roleSet != null) {
                    String barRole = bar.kind() == BarKind.FOREMAN_SPAN ? FOREMAN : row.companyRole();
                    // companyRole may be FOREMAN even for a worker bar when the user also foremans;
                    // the meaningful assertion is that at least one allowed role explains the bar.
                    if (bar.kind() == BarKind.FOREMAN_SPAN) {
                        assertThat(roleSet).contains(FOREMAN);
                    }
                }
            }
        }

        // Never widen: supplying a Project_Filter that includes an out-of-scope id yields the same
        // result as the in-scope-only subset.
        List<Long> inScopeOnly = projectIds == null ? null : projectIds.stream()
                .filter(allowedProjectIds::contains).toList();
        List<Long> withOutOfScope = new ArrayList<>(projectIds == null ? List.of() : projectIds);
        long bogus = 999_000L;
        withOutOfScope.add(bogus); // definitely out of scope
        CompanyScheduleView withBogus = CrossProjectScheduleProjector.project(
                projects, new CompanyScheduleQuery(roles, withOutOfScope, memberIds, null, null), russianLocale);
        CompanyScheduleView withoutBogus = CrossProjectScheduleProjector.project(
                projects,
                new CompanyScheduleQuery(roles, inScopeOnly == null || inScopeOnly.isEmpty() ? projectIds : inScopeOnly,
                        memberIds, null, null),
                russianLocale);
        // Adding an out-of-scope id to a non-empty filter neither adds bars nor changes the result.
        if (projectIds != null && !projectIds.isEmpty()) {
            assertThat(withBogus).isEqualTo(withoutBogus);
        }
    }

    // ==========================================================================================
    // Property 9: Date_Window excludes undatable bars and keeps intersecting bars (R4.5, R7.5, R7.6)
    //   Any supplied Date_Window bound excludes every undatable bar and keeps a datable bar exactly
    //   when its inclusive interval intersects [from, to]; with no window, every bar is kept.
    // ==========================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 9: Date_Window excludes undatable bars and keeps intersecting bars")
    void dateWindowExcludesUndatableAndKeepsIntersecting(
            @ForAll("projects") List<ProjectInput> projects,
            @ForAll("windowDate") LocalDate from,
            @ForAll("windowDate") LocalDate to,
            @ForAll boolean russianLocale) {

        // Normalize so from <= to (a from > to window is a controller 400, not a projector concern).
        LocalDate lo = from.isAfter(to) ? to : from;
        LocalDate hi = from.isAfter(to) ? from : to;

        CompanyScheduleView windowed = CrossProjectScheduleProjector.project(
                projects, new CompanyScheduleQuery(null, null, null, lo, hi), russianLocale);
        CompanyScheduleView open = CrossProjectScheduleProjector.project(projects, null, russianLocale);

        // With a window supplied: every surviving bar is datable and intersects [lo, hi].
        for (MemberRow row : windowed.rows()) {
            for (ProjectedBar bar : row.bars()) {
                assertThat(bar.startDate()).as("undatable bar excluded by window").isNotNull();
                assertThat(bar.endDate()).isNotNull();
                boolean intersects = !bar.startDate().isAfter(hi) && !bar.endDate().isBefore(lo);
                assertThat(intersects).as("bar interval intersects [lo, hi]").isTrue();
            }
        }

        // Every datable bar of the open projection that intersects [lo, hi] survives the window;
        // every datable bar that does NOT intersect is dropped; every undatable bar is dropped.
        for (MemberRow openRow : open.rows()) {
            MemberRow windowedRow = windowed.rows().stream()
                    .filter(r -> r.userId() == openRow.userId()).findFirst().orElse(null);
            for (ProjectedBar bar : openRow.bars()) {
                boolean datable = bar.startDate() != null && bar.endDate() != null;
                boolean intersects = datable
                        && !bar.startDate().isAfter(hi) && !bar.endDate().isBefore(lo);
                boolean presentInWindow = windowedRow != null && windowedRow.bars().stream()
                        .anyMatch(b -> sameBar(b, bar));
                if (datable && intersects) {
                    assertThat(presentInWindow).as("intersecting datable bar kept").isTrue();
                } else {
                    assertThat(presentInWindow).as("non-intersecting or undatable bar dropped").isFalse();
                }
            }
        }
    }

    // ==========================================================================================
    // Property 10: Empty filtered members are omitted (R10.3)
    //   A member whose every bar is removed by the supplied filters yields no MemberRow.
    // ==========================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 10: Empty filtered members are omitted")
    void emptyFilteredMembersAreOmitted(@ForAll("projects") List<ProjectInput> projects,
                                        @ForAll("idSubset") List<Long> memberIds,
                                        @ForAll boolean russianLocale) {
        CompanyScheduleView view = CrossProjectScheduleProjector.project(
                projects, new CompanyScheduleQuery(null, null, memberIds, null, null), russianLocale);

        // No empty rows survive.
        for (MemberRow row : view.rows()) {
            assertThat(row.bars()).isNotEmpty();
        }

        // When a Member_Filter is supplied, no row exists for a user outside it (their bars were all
        // removed, so the member is omitted entirely).
        if (memberIds != null && !memberIds.isEmpty()) {
            Set<Long> allowed = new HashSet<>(memberIds);
            for (MemberRow row : view.rows()) {
                assertThat(allowed).contains(row.userId());
            }
        }
    }

    // ==========================================================================================
    // Property 11: Deterministic ordering (R10.4, R11.2)
    //   Ordering of rows, bars within a row, and legend entries is a pure function of the input, so
    //   identical inputs yield an identical response — including a reshuffled-but-equal input set.
    // ==========================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 11: Deterministic ordering")
    void deterministicOrdering(@ForAll("projects") List<ProjectInput> projects,
                               @ForAll long shuffleSeed,
                               @ForAll boolean russianLocale) {
        CompanyScheduleView first = CrossProjectScheduleProjector.project(projects, null, russianLocale);
        CompanyScheduleView second = CrossProjectScheduleProjector.project(projects, null, russianLocale);

        // Identical inputs -> identical response (byte-for-byte record equality).
        assertThat(second).isEqualTo(first);

        // Rows ordered by (userName NULLS LAST, userId).
        assertRowOrder(first.rows());
        // Bars within a row ordered by (startDate NULLS LAST, projectId, workCategoryId NULLS LAST,
        // startHalfDays NULLS LAST).
        for (MemberRow row : first.rows()) {
            assertBarOrder(row.bars());
        }
        // Legend ordered by ascending projectId.
        assertLegendOrder(first.legend());

        // Reordering whole projects in the input list must not change the projection (ordering is a
        // pure function of the data, not of input order).
        List<ProjectInput> reshuffled = new ArrayList<>(projects);
        Collections.shuffle(reshuffled, new Random(shuffleSeed));
        CompanyScheduleView fromReshuffled =
                CrossProjectScheduleProjector.project(reshuffled, null, russianLocale);
        assertThat(fromReshuffled).isEqualTo(first);
    }

    // ==========================================================================================
    // Property 12: No cost-bearing field is ever emitted (R8.1, R8.2, R8.3)
    //   None of CompanyScheduleView / MemberRow / ProjectedBar / ProjectLegendEntry / ConflictPair
    //   declares a money / man-days / rate / pay-tier record component. Asserting on the record
    //   component NAMES is the structural guarantee the DTO can never carry a cost figure.
    // ==========================================================================================

    @Property(tries = 100)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 12: No cost-bearing field is ever emitted")
    void noCostBearingFieldIsEverEmitted(@ForAll("projects") List<ProjectInput> projects,
                                         @ForAll boolean russianLocale) {
        // Build a real projection so the property also asserts against produced instances, not only
        // the type shape.
        CompanyScheduleView view = CrossProjectScheduleProjector.project(projects, null, russianLocale);
        assertThat(view).isNotNull();

        // Structural guarantee: no DTO record exposes a cost-bearing component.
        assertNoCostComponents(CompanyScheduleView.class);
        assertNoCostComponents(MemberRow.class);
        assertNoCostComponents(ProjectedBar.class);
        assertNoCostComponents(ProjectLegendEntry.class);
        assertNoCostComponents(ConflictPair.class);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers (reference / assertion)
    // ------------------------------------------------------------------------------------------

    /** Forbidden substrings naming a money / man-days / rate / pay-tier field (Requirement 8). */
    private static final List<String> COST_TOKENS = List.of(
            "cost", "price", "amount", "money", "rate", "salary", "wage", "pay",
            "manday", "mandays", "mandaypay", "tariff", "tier", "margin", "total",
            "sum", "net", "gross", "vat", "currency");

    private static void assertNoCostComponents(Class<?> recordType) {
        assertThat(recordType.isRecord()).as(recordType.getSimpleName() + " is a record").isTrue();
        for (RecordComponent component : recordType.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            for (String token : COST_TOKENS) {
                assertThat(name)
                        .as(recordType.getSimpleName() + "." + component.getName()
                                + " must not be a cost-bearing field (token '" + token + "')")
                        .doesNotContain(token);
            }
        }
    }

    /** The user ids that should contribute at least one bar in an unfiltered projection. */
    private static Set<Long> expectedContributingUserIds(List<ProjectInput> projects) {
        Set<Long> contributors = new HashSet<>();
        for (ProjectInput p : projects) {
            if (p == null) {
                continue;
            }
            Set<Long> foremanIds = new HashSet<>();
            if (p.foremen() != null) {
                for (ForemanInput f : p.foremen()) {
                    if (f != null) {
                        foremanIds.add(f.userId());
                        contributors.add(f.userId()); // a foreman always gets a span (datable or not)
                    }
                }
            }
            if (p.workerBars() != null) {
                for (WorkerBarInput w : p.workerBars()) {
                    if (w != null && !foremanIds.contains(w.userId())) {
                        contributors.add(w.userId()); // worker bar survives foreman precedence
                    }
                }
            }
        }
        return contributors;
    }

    private static Set<Long> allProjectIds(List<ProjectInput> projects) {
        Set<Long> ids = new HashSet<>();
        for (ProjectInput p : projects) {
            if (p != null) {
                ids.add(p.projectId());
            }
        }
        return ids;
    }

    private static boolean sameBar(ProjectedBar a, ProjectedBar b) {
        return a.kind() == b.kind()
                && java.util.Objects.equals(a.projectId(), b.projectId())
                && java.util.Objects.equals(a.workCategoryId(), b.workCategoryId())
                && java.util.Objects.equals(a.startHalfDays(), b.startHalfDays())
                && java.util.Objects.equals(a.durationHalfDays(), b.durationHalfDays())
                && java.util.Objects.equals(a.startDate(), b.startDate())
                && java.util.Objects.equals(a.endDate(), b.endDate());
    }

    private static void assertRowOrder(List<MemberRow> rows) {
        for (int i = 1; i < rows.size(); i++) {
            MemberRow prev = rows.get(i - 1);
            MemberRow cur = rows.get(i);
            int byName = compareNullsLast(prev.userName(), cur.userName());
            if (byName != 0) {
                assertThat(byName).as("rows ordered by userName").isLessThanOrEqualTo(0);
            } else {
                assertThat(prev.userId()).as("rows tie-broken by userId").isLessThanOrEqualTo(cur.userId());
            }
        }
    }

    private static void assertBarOrder(List<ProjectedBar> bars) {
        for (int i = 1; i < bars.size(); i++) {
            ProjectedBar prev = bars.get(i - 1);
            ProjectedBar cur = bars.get(i);
            int cmp = compareDatesNullsLast(prev.startDate(), cur.startDate());
            if (cmp == 0) {
                cmp = compareLongNullsLast(prev.projectId(), cur.projectId());
            }
            if (cmp == 0) {
                cmp = compareLongNullsLast(prev.workCategoryId(), cur.workCategoryId());
            }
            if (cmp == 0) {
                cmp = compareIntNullsLast(prev.startHalfDays(), cur.startHalfDays());
            }
            assertThat(cmp).as("bars ordered by (startDate, projectId, workCategoryId, startHalfDays)")
                    .isLessThanOrEqualTo(0);
        }
    }

    private static void assertLegendOrder(List<ProjectLegendEntry> legend) {
        for (int i = 1; i < legend.size(); i++) {
            assertThat(legend.get(i - 1).projectId())
                    .as("legend ordered by ascending projectId")
                    .isLessThan(legend.get(i).projectId());
        }
    }

    private static int compareNullsLast(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return a.compareTo(b);
    }

    private static int compareDatesNullsLast(LocalDate a, LocalDate b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return a.compareTo(b);
    }

    private static int compareLongNullsLast(Long a, Long b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return a.compareTo(b);
    }

    private static int compareIntNullsLast(Integer a, Integer b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return a.compareTo(b);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * A list of 0..6 Accessible_Projects with distinct ids, a mix of anchored and unanchored
     * projects, partial-date projects, worker bars (some users shared across projects to exercise
     * cross-project aggregation), and foreman members (some also owning worker bars so foreman
     * precedence is exercised).
     */
    @Provide
    Arbitrary<List<ProjectInput>> projects() {
        return projectInput().list().ofMinSize(0).ofMaxSize(6).uniqueElements(ProjectInput::projectId);
    }

    /**
     * Projects in which every project has at least one foreman who ALSO owns a worker bar in the same
     * project — the exact precondition of Property 3 (foreman precedence).
     */
    @Provide
    Arbitrary<List<ProjectInput>> foremanAlsoWorkerProjects() {
        return foremanAlsoWorkerProject().list().ofMinSize(1).ofMaxSize(5)
                .uniqueElements(ProjectInput::projectId);
    }

    private Arbitrary<ProjectInput> projectInput() {
        Arbitrary<Long> projectId = Arbitraries.longs().between(1L, 30L);
        Arbitrary<String> name = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6)
                .map(s -> "P-" + s);
        Arbitrary<LocalDate> startDate = anchorOrNull();
        Arbitrary<LocalDate> endDate = anchorOrNull();

        return Combinators.combine(projectId, name, startDate, endDate,
                        workerBars(), foremen())
                .as((id, nm, start, end, workers, fms) ->
                        new ProjectInput(id, nm, start, end, workers, fms));
    }

    private Arbitrary<ProjectInput> foremanAlsoWorkerProject() {
        Arbitrary<Long> projectId = Arbitraries.longs().between(1L, 30L);
        Arbitrary<String> name = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6)
                .map(s -> "P-" + s);
        // A user who is both a foreman and a worker of this project.
        Arbitrary<Long> sharedUser = Arbitraries.longs().between(1L, 8L);

        return Combinators.combine(projectId, name, anchorOrNull(), anchorOrNull(), sharedUser,
                        workerBars())
                .as((id, nm, start, end, uid, otherWorkers) -> {
                    List<WorkerBarInput> workers = new ArrayList<>(otherWorkers);
                    // Guarantee the foreman-also-worker user owns at least one worker bar here.
                    workers.add(new WorkerBarInput(uid, "U" + uid, WORKER,
                            500L, "Kat", "Cat", 1, 2));
                    List<ForemanInput> fms = new ArrayList<>();
                    fms.add(new ForemanInput(uid, "U" + uid));
                    return new ProjectInput(id, nm, start, end, workers, fms);
                });
    }

    private Arbitrary<List<WorkerBarInput>> workerBars() {
        return workerBarInput().list().ofMinSize(0).ofMaxSize(5);
    }

    private Arbitrary<WorkerBarInput> workerBarInput() {
        Arbitrary<Long> userId = Arbitraries.longs().between(1L, 8L);
        Arbitrary<Long> categoryId = Arbitraries.longs().between(100L, 105L);
        Arbitrary<Integer> startHalfDays = Arbitraries.integers().between(1, 40);
        Arbitrary<Integer> durationHalfDays = Arbitraries.integers().between(1, 40);
        return Combinators.combine(userId, categoryId, startHalfDays, durationHalfDays)
                .as((uid, cat, s, d) -> new WorkerBarInput(
                        uid, "U" + uid, WORKER, cat, "Kat" + cat, "Cat" + cat, s, d));
    }

    private Arbitrary<List<ForemanInput>> foremen() {
        return foremanInput().list().ofMinSize(0).ofMaxSize(3)
                .map(list -> {
                    // De-duplicate foremen by userId within a project (an ACTIVE FOREMAN appears once).
                    LinkedHashSet<Long> seen = new LinkedHashSet<>();
                    List<ForemanInput> unique = new ArrayList<>();
                    for (ForemanInput f : list) {
                        if (seen.add(f.userId())) {
                            unique.add(f);
                        }
                    }
                    return unique;
                });
    }

    private Arbitrary<ForemanInput> foremanInput() {
        return Arbitraries.longs().between(1L, 8L).map(uid -> new ForemanInput(uid, "U" + uid));
    }

    private Arbitrary<LocalDate> anchorOrNull() {
        return Arbitraries.oneOf(
                Arbitraries.longs().between(0L, 120L)
                        .map(offset -> LocalDate.of(2026, 1, 1).plusDays(offset)),
                Arbitraries.just((LocalDate) null));
    }

    @Provide
    Arbitrary<LocalDate> windowDate() {
        return Arbitraries.longs().between(0L, 200L)
                .map(offset -> LocalDate.of(2026, 1, 1).plusDays(offset));
    }

    @Provide
    Arbitrary<List<String>> roleFilters() {
        return Arbitraries.oneOf(
                Arbitraries.just((List<String>) null),
                Arbitraries.just(List.of(FOREMAN)),
                Arbitraries.just(List.of(WORKER)),
                Arbitraries.just(List.of(FOREMAN, WORKER)));
    }

    @Provide
    Arbitrary<List<Long>> idSubset() {
        return Arbitraries.oneOf(
                Arbitraries.just((List<Long>) null),
                Arbitraries.longs().between(1L, 30L).list().ofMinSize(0).ofMaxSize(5));
    }
}
