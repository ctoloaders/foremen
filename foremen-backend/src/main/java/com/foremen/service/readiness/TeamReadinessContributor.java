package com.foremen.service.readiness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.controller.model.TeamReadiness;
import com.foremen.service.ProjectMemberService;

/**
 * The {@code team} area's readiness contribution (FOR-05-11 Requirement 5.5; FOR-05-10b
 * Requirement 8.1/8.3/8.4).
 *
 * <p>Delegates to the FOR-05-09 {@link ProjectMemberService#readiness(Long)} so the area's own rule
 * stays in one place. The {@code team} area is
 * {@link ReadinessState#DONE} only when the project has <em>both</em> at least one ACTIVE
 * {@code FOREMAN} member <em>and</em> at least one ACTIVE {@code WORKER} member; otherwise it is
 * {@link ReadinessState#BLOCKED} and every applicable blocker is reported (R8.3, R8.4 — show all
 * blockers):
 *
 * <ul>
 *   <li>at least one ACTIVE FOREMAN and at least one ACTIVE WORKER → {@link ReadinessState#DONE},
 *       {@code 100%}, no items.</li>
 *   <li>no ACTIVE FOREMAN → {@link ReadinessState#BLOCKED}, {@code 0%}, a
 *       {@code readiness.team.noForeman} item (assign an active foreman).</li>
 *   <li>no ACTIVE WORKER → {@link ReadinessState#BLOCKED}, {@code 0%}, a
 *       {@code readiness.team.noWorkers} item (assign at least one active worker).</li>
 *   <li>neither present → both items appear together.</li>
 * </ul>
 *
 * <p>All items target the {@code team} workspace tab. This contributor is side-effect free: the
 * delegated {@code readiness} read changes no data (Requirement 1.6).
 */
@Component
public class TeamReadinessContributor implements ProjectReadinessContributor {

    static final String AREA_KEY = "team";
    static final String NAME_KEY = "workspace.tab.team";
    static final String TARGET_TAB = "team";
    static final String ITEM_NO_FOREMAN = "readiness.team.noForeman";
    static final String ITEM_NO_WORKERS = "readiness.team.noWorkers";

    private final ProjectMemberService projectMemberService;

    public TeamReadinessContributor(ProjectMemberService projectMemberService) {
        this.projectMemberService = projectMemberService;
    }

    @Override
    public String areaKey() {
        return AREA_KEY;
    }

    @Override
    public String areaResource() {
        return "PROJECT_MEMBERS";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public AreaContribution contribute(Long projectId) {
        TeamReadiness teamReadiness = projectMemberService.readiness(projectId);
        boolean hasActiveForeman = teamReadiness.counts().foreman() >= 1;
        int activeWorkerCount = teamReadiness.counts().worker();
        return deriveContribution(hasActiveForeman, activeWorkerCount);
    }

    /**
     * The pure derivation of the {@code team} contribution from the ACTIVE member counts.
     * Spring-free so the gate → state/percentage/items mapping is directly unit-testable
     * (FOR-05-11 Requirement 5.5; FOR-05-10b Requirement 8.1/8.3/8.4).
     *
     * <p>The area is {@code DONE}/100% only when both an ACTIVE FOREMAN and an ACTIVE WORKER are
     * present. Otherwise it is {@code BLOCKED}/0% and each missing role contributes its own
     * outstanding item; both can appear together (R8.3, R8.4).
     *
     * @param hasActiveForeman  whether the project has at least one ACTIVE {@code FOREMAN} member
     * @param activeWorkerCount the number of ACTIVE {@code WORKER} members of the project
     * @return the well-formed {@code team} area contribution
     */
    static AreaContribution deriveContribution(boolean hasActiveForeman, int activeWorkerCount) {
        boolean hasActiveWorker = activeWorkerCount >= 1;
        if (hasActiveForeman && hasActiveWorker) {
            return new AreaContribution(AREA_KEY, NAME_KEY, 100, ReadinessState.DONE, List.of());
        }

        // BLOCKED — report every applicable blocker (R8.3, R8.4). Foreman first (existing order),
        // then workers, so the item order is stable.
        List<OutstandingItem> items = new ArrayList<>();
        if (!hasActiveForeman) {
            items.add(new OutstandingItem(ITEM_NO_FOREMAN, Map.of(), TARGET_TAB));
        }
        if (!hasActiveWorker) {
            items.add(new OutstandingItem(ITEM_NO_WORKERS, Map.of(), TARGET_TAB));
        }
        return new AreaContribution(AREA_KEY, NAME_KEY, 0, ReadinessState.BLOCKED, List.copyOf(items));
    }
}
