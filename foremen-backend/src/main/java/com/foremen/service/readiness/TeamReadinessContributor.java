package com.foremen.service.readiness;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.controller.model.TeamReadiness;
import com.foremen.service.ProjectMemberService;

/**
 * The {@code team} area's readiness contribution (FOR-05-11 Requirement 5.5).
 *
 * <p>Delegates to the FOR-05-09 {@link ProjectMemberService#readiness(Long)} so the area's own rule
 * stays in one place: the {@code team} gate is {@link com.foremen.service.team.ReadinessState#DONE}
 * iff the project has at least one ACTIVE {@code FOREMAN} member and
 * {@link com.foremen.service.team.ReadinessState#BLOCKED} otherwise. The team enum (which has only
 * {@code DONE}/{@code BLOCKED}) is mapped onto the shared three-valued {@link ReadinessState}:
 *
 * <ul>
 *   <li>{@code DONE} → {@link ReadinessState#DONE}, {@code 100%}, no items (an active foreman is
 *       assigned).</li>
 *   <li>{@code BLOCKED} → {@link ReadinessState#BLOCKED}, {@code 0%}, one
 *       {@code readiness.team.noForeman} item (assign an active foreman).</li>
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
        return deriveContribution(teamReadiness.state());
    }

    /**
     * The pure derivation of the {@code team} contribution from the FOR-05-09 team gate state.
     * Spring-free so the state → state/percentage mapping is directly unit-testable
     * (FOR-05-11 Requirement 5.5).
     *
     * @param teamState the team gate state ({@code DONE} / {@code BLOCKED})
     * @return the well-formed {@code team} area contribution
     */
    static AreaContribution deriveContribution(com.foremen.service.team.ReadinessState teamState) {
        if (teamState == com.foremen.service.team.ReadinessState.DONE) {
            return new AreaContribution(AREA_KEY, NAME_KEY, 100, ReadinessState.DONE, List.of());
        }
        // BLOCKED — no ACTIVE FOREMAN assigned
        return new AreaContribution(
                AREA_KEY,
                NAME_KEY,
                0,
                ReadinessState.BLOCKED,
                List.of(new OutstandingItem(ITEM_NO_FOREMAN, Map.of(), TARGET_TAB)));
    }
}
