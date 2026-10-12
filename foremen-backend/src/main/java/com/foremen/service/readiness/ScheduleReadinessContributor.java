package com.foremen.service.readiness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.controller.model.schedule.ScheduleReadiness;
import com.foremen.controller.model.schedule.UncoveredCategoryRef;
import com.foremen.service.ProjectScheduleService;

/**
 * The {@code schedule} area's readiness contribution (FOR-05-11 Requirement 5.6).
 *
 * <p>Delegates to the FOR-05-10 {@link ProjectScheduleService#readiness(Long)} so the area's own
 * rule stays in one place. The schedule gate reports {@code rowCount} (current schedule rows) and
 * {@code scheduledCount} (rows carrying a bar) together with its three-valued
 * {@link ScheduleReadiness.State}, which maps 1:1 onto the shared {@link ReadinessState}:
 *
 * <ul>
 *   <li>{@link ScheduleReadiness.State#DONE} → {@link ReadinessState#DONE} (every row scheduled;
 *       {@code 100%}, no items).</li>
 *   <li>{@link ScheduleReadiness.State#PARTIAL} → {@link ReadinessState#PARTIAL}.</li>
 *   <li>{@link ScheduleReadiness.State#BLOCKED} → {@link ReadinessState#BLOCKED} (nothing scheduled
 *       or no rows).</li>
 * </ul>
 *
 * <p>The percentage is the scheduled fraction {@code round(100 × scheduledCount / rowCount)}, and
 * {@code 0} when there are no rows (Decision D3). When the area is not {@code DONE} it lists one
 * {@code readiness.schedule.unscheduledRows} item whose {@code count} is the number of rows without
 * a bar ({@code rowCount − scheduledCount}), targeting the {@code scheduleDesign} workspace tab.
 *
 * <p>FOR-05-10b (R8.2) adds the coverage dimension: for every {@code Uncovered_Category} (an
 * estimate work category that no ACTIVE WORKER covers) the area lists one
 * {@code readiness.schedule.noWorkerForCategory} item whose {@code category} param is the localized
 * category name, targeting the {@code team} tab (that is where a worker's work categories are
 * assigned). Because planning cannot complete while a category has no worker, the area state is
 * forced to {@link ReadinessState#BLOCKED} whenever any category is uncovered — the unscheduled-rows
 * item (when present) and the per-category items are shown together so every blocker is visible
 * (R8.3/R8.4). This contributor is side-effect free: the delegated {@code readiness} read changes no
 * data (Requirement 1.6).
 */
@Component
public class ScheduleReadinessContributor implements ProjectReadinessContributor {

    static final String AREA_KEY = "schedule";
    static final String NAME_KEY = "workspace.tab.scheduleDesign";
    static final String TARGET_TAB = "scheduleDesign";
    static final String ITEM_UNSCHEDULED_ROWS = "readiness.schedule.unscheduledRows";
    static final String ITEM_NO_WORKER_FOR_CATEGORY = "readiness.schedule.noWorkerForCategory";
    /** The Team tab is where a worker's work categories are assigned, so the uncovered item points there (R8.2). */
    static final String TEAM_TAB = "team";

    private final ProjectScheduleService projectScheduleService;

    public ScheduleReadinessContributor(ProjectScheduleService projectScheduleService) {
        this.projectScheduleService = projectScheduleService;
    }

    @Override
    public String areaKey() {
        return AREA_KEY;
    }

    @Override
    public String areaResource() {
        return "WORK_SCHEDULE";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public AreaContribution contribute(Long projectId) {
        return deriveContribution(projectScheduleService.readiness(projectId));
    }

    /**
     * The pure derivation of the {@code schedule} contribution from the FOR-05-10/10b schedule gate.
     * Spring-free so the state/fraction/items mapping is directly unit-testable (FOR-05-11 Req 5.6,
     * FOR-05-10b R8.2). The schedule state maps 1:1 onto {@link ReadinessState}, except that any
     * {@code Uncovered_Category} forces {@link ReadinessState#BLOCKED} (planning cannot complete
     * without a worker for a category). The percentage is the scheduled fraction (0 when there are no
     * rows). Items: an {@code unscheduledRows} item whenever rows remain unscheduled, plus one
     * {@code noWorkerForCategory} item per uncovered category — shown together so every blocker is
     * visible (R8.3/R8.4).
     *
     * @param scheduleReadiness the FOR-05-10/10b schedule gate (row/scheduled counts, state, and the
     *                          uncovered categories with localized names)
     * @return the well-formed {@code schedule} area contribution
     */
    static AreaContribution deriveContribution(ScheduleReadiness scheduleReadiness) {
        int rowCount = scheduleReadiness.rowCount();
        int scheduledCount = scheduleReadiness.scheduledCount();
        List<UncoveredCategoryRef> uncovered = scheduleReadiness.uncoveredCategories();
        boolean anyUncovered = uncovered != null && !uncovered.isEmpty();

        // Any uncovered category forces BLOCKED (R8.2); otherwise the FOR-05-10 state maps 1:1.
        ReadinessState state = anyUncovered ? ReadinessState.BLOCKED : mapState(scheduleReadiness.state());
        int percentage = rowCount == 0
                ? 0
                : Math.toIntExact(Math.round(100.0 * scheduledCount / rowCount));

        if (state == ReadinessState.DONE) {
            return new AreaContribution(AREA_KEY, NAME_KEY, 100, ReadinessState.DONE, List.of());
        }

        // All blockers are shown together (R8.3/R8.4): the unscheduled-rows item whenever rows
        // remain unscheduled (every row scheduled with 0 unscheduled would be noise, so it is
        // omitted then — the FOR-05-10 behavior emits it only when the gate itself is not DONE), and
        // one noWorkerForCategory item per Uncovered_Category.
        List<OutstandingItem> items = new ArrayList<>();
        int unscheduled = rowCount - scheduledCount;
        if (scheduleReadiness.state() != ScheduleReadiness.State.DONE) {
            items.add(new OutstandingItem(
                    ITEM_UNSCHEDULED_ROWS, Map.of("count", unscheduled), TARGET_TAB));
        }
        if (anyUncovered) {
            for (UncoveredCategoryRef ref : uncovered) {
                items.add(new OutstandingItem(
                        ITEM_NO_WORKER_FOR_CATEGORY,
                        Map.of("category", ref.name() == null ? "" : ref.name()),
                        TEAM_TAB));
            }
        }
        return new AreaContribution(AREA_KEY, NAME_KEY, percentage, state, List.copyOf(items));
    }

    /**
     * Maps the FOR-05-10 schedule state 1:1 onto the shared aggregate {@link ReadinessState}.
     *
     * @param state the schedule gate state
     * @return the equivalent shared readiness state
     */
    private static ReadinessState mapState(ScheduleReadiness.State state) {
        return switch (state) {
            case DONE -> ReadinessState.DONE;
            case PARTIAL -> ReadinessState.PARTIAL;
            case BLOCKED -> ReadinessState.BLOCKED;
        };
    }
}
