package com.foremen.service.readiness;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.controller.model.schedule.ScheduleReadiness;
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
 * a bar ({@code rowCount − scheduledCount}). All items target the {@code scheduleDesign} workspace
 * tab. This contributor is side-effect free: the delegated {@code readiness} read changes no data
 * (Requirement 1.6).
 */
@Component
public class ScheduleReadinessContributor implements ProjectReadinessContributor {

    static final String AREA_KEY = "schedule";
    static final String NAME_KEY = "workspace.tab.scheduleDesign";
    static final String TARGET_TAB = "scheduleDesign";
    static final String ITEM_UNSCHEDULED_ROWS = "readiness.schedule.unscheduledRows";

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
     * The pure derivation of the {@code schedule} contribution from the FOR-05-10 schedule gate.
     * Spring-free so the state/fraction mapping is directly unit-testable (FOR-05-11 Requirement
     * 5.6). The schedule state maps 1:1 onto {@link ReadinessState}; the percentage is the scheduled
     * fraction (0 when there are no rows); an {@code unscheduledRows} item surfaces whenever the area
     * is not {@code DONE}.
     *
     * @param scheduleReadiness the FOR-05-10 schedule gate (row/scheduled counts and state)
     * @return the well-formed {@code schedule} area contribution
     */
    static AreaContribution deriveContribution(ScheduleReadiness scheduleReadiness) {
        int rowCount = scheduleReadiness.rowCount();
        int scheduledCount = scheduleReadiness.scheduledCount();
        ReadinessState state = mapState(scheduleReadiness.state());
        int percentage = rowCount == 0
                ? 0
                : Math.toIntExact(Math.round(100.0 * scheduledCount / rowCount));

        if (state == ReadinessState.DONE) {
            return new AreaContribution(AREA_KEY, NAME_KEY, 100, ReadinessState.DONE, List.of());
        }

        int unscheduled = rowCount - scheduledCount;
        return new AreaContribution(
                AREA_KEY,
                NAME_KEY,
                percentage,
                state,
                List.of(new OutstandingItem(
                        ITEM_UNSCHEDULED_ROWS, Map.of("count", unscheduled), TARGET_TAB)));
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
