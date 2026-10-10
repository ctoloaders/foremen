package com.foremen.service.readiness;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.permission.ForemenPermissionEvaluator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit and property-based tests for {@link ProjectReadinessService} (FOR-05-11 task 3.3), covering
 * the three service-level correctness properties plus the empty-areas and 404-gate behaviors.
 *
 * <p>The collaborators ({@link ForemenPermissionEvaluator}, {@link ProjectAccessCache},
 * {@link ProjectDao}) are mocked with Mockito and the {@code List<ProjectReadinessContributor>} is
 * supplied as controllable {@link FakeContributor} test doubles so the service's composition,
 * ordering, and scoping can be exercised without a Spring context. The caller role / ADMIN flag is
 * driven through {@link SecurityContextHolder}, mirroring {@code ProjectScheduleServiceTest}. jqwik
 * is not Spring-wired in this project, so the mocks are built per-property inside the test body
 * rather than through {@code @Mock}, keeping the jqwik and Mockito lifecycles independent.
 *
 * <ul>
 *   <li><b>Property 1 — contribution well-formedness contract.</b> Every area the service returns is
 *       a well-formed {@link AreaContribution}: {@code percentage ∈ [0, 100]}, a valid
 *       {@link ReadinessState}, and empty {@code items} iff {@code DONE} at {@code 100%}
 *       (Requirements 1.2, 1.3).</li>
 *   <li><b>Property 5 — order independence.</b> Permuting the contributor bean list leaves the
 *       composed {@code overallPercentage} / {@code overallState} unchanged and the returned
 *       {@code areas} always in declared {@code order()} sequence (Requirements 7.1, 7.4).</li>
 *   <li><b>Property 6 — permission scoping.</b> For any subset of granted area resources the
 *       included areas are exactly those the caller may READ; nothing about an omitted area appears
 *       in the view (Requirements 6.2, 6.3).</li>
 * </ul>
 *
 * <p>Plus unit tests: no readable area → {@code 200} + {@code 0%} + {@code BLOCKED} (Requirement
 * 6.4, 4.4) and the {@code 404 error.entity.not.found} access gate for a missing or inaccessible
 * project (Requirements 3.2, 3.3).
 *
 * <p>Feature: FOR-05-11-project-readiness, Properties 1, 5, 6
 *
 * <p><b>Validates: Requirements 1.2, 1.3, 3.2, 3.3, 4.4, 6.2, 6.3, 6.4, 7.1, 7.4</b>
 */
@Tag("Feature: FOR-05-11-project-readiness, Properties 1, 5, 6")
class ProjectReadinessServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long USER_ID = 7L;

    /** The six design-stage areas, each (areaKey, areaResource, declared order). */
    private static final List<AreaSpec> AREA_SPECS = List.of(
            new AreaSpec("rooms", "ROOMS", 10),
            new AreaSpec("estimate", "ESTIMATE", 20),
            new AreaSpec("offer", "OFFERS", 30),
            new AreaSpec("documents", "SIGNABLE_DOCUMENTS", 40),
            new AreaSpec("team", "PROJECT_MEMBERS", 50),
            new AreaSpec("schedule", "WORK_SCHEDULE", 60));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // =======================================================================
    // Property 1 — contribution well-formedness contract across contributors
    // =======================================================================

    @Property(tries = 200)
    @Tag("Property 1")
    void everyReturnedAreaIsAWellFormedContribution(@ForAll("contributionList") List<AreaContribution> source) {
        // An ADMIN caller reads every area, so each contributor's output flows through the service.
        authenticateAdmin();

        List<ProjectReadinessContributor> contributors = contributorsReturning(source);
        ProjectReadinessService service = adminService(contributors);

        ProjectReadinessResult result = service.getReadiness(PROJECT_ID);

        for (AreaContribution area : result.areas()) {
            assertThat(area.percentage()).isBetween(0, 100);
            assertThat(area.state()).isIn(ReadinessState.DONE, ReadinessState.PARTIAL, ReadinessState.BLOCKED);
            boolean fullyReady = area.state() == ReadinessState.DONE && area.percentage() == 100;
            assertThat(area.items().isEmpty()).isEqualTo(fullyReady);
        }
        // The overall headline is itself well-formed and consistent with its areas.
        assertThat(result.overallPercentage()).isBetween(0, 100);
        assertThat(result.overallState())
                .isEqualTo(ReadinessMath.overallState(result.areas()));
        assertThat(result.overallPercentage())
                .isEqualTo(ReadinessMath.overallPercentage(result.areas()));
    }

    // =======================================================================
    // Property 5 — order independence of composition and declared-order output
    // =======================================================================

    @Property(tries = 200)
    @Tag("Property 5")
    void compositionAndOrderAreIndependentOfDiscoveryOrder(
            @ForAll("contributionList") List<AreaContribution> source) {
        authenticateAdmin();

        List<ProjectReadinessContributor> inDeclaredOrder = contributorsReturning(source);
        List<ProjectReadinessContributor> shuffled = new ArrayList<>(inDeclaredOrder);
        Collections.reverse(shuffled);

        ProjectReadinessResult natural = adminService(inDeclaredOrder).getReadiness(PROJECT_ID);
        ProjectReadinessResult reversed = adminService(shuffled).getReadiness(PROJECT_ID);

        // Composition is permutation-invariant.
        assertThat(reversed.overallPercentage()).isEqualTo(natural.overallPercentage());
        assertThat(reversed.overallState()).isEqualTo(natural.overallState());

        // The returned areas are byte-for-byte identical regardless of discovery order.
        assertThat(reversed.areas()).isEqualTo(natural.areas());

        // And they are in strictly ascending declared order().
        List<Integer> orders = natural.areas().stream()
                .map(area -> AREA_SPECS.stream()
                        .filter(s -> s.key().equals(area.key()))
                        .findFirst().orElseThrow().order())
                .toList();
        assertThat(orders).isSorted();
    }

    // =======================================================================
    // Property 6 — permission scoping (included == readable, nothing leaks)
    // =======================================================================

    @Property(tries = 300)
    @Tag("Property 6")
    void includedAreasAreExactlyTheReadableOnesAndNothingLeaks(
            @ForAll("grantedResources") Set<String> grantedResources) {
        // A non-ADMIN caller: readable areas are exactly those whose resource is granted READ.
        authenticateRole("MANAGER");

        List<AreaContribution> source = AREA_SPECS.stream()
                .map(spec -> contribution(spec.key(), ReadinessState.PARTIAL, 50))
                .toList();
        List<ProjectReadinessContributor> contributors = contributorsReturning(source);

        ForemenPermissionEvaluator evaluator = mock(ForemenPermissionEvaluator.class);
        for (AreaSpec spec : AREA_SPECS) {
            when(evaluator.isAllowed("MANAGER", spec.resource(), "READ"))
                    .thenReturn(grantedResources.contains(spec.resource()));
        }
        ProjectDao projectDao = projectDaoFound();
        ProjectAccessCache accessCache = accessibleProjectCache();
        ProjectReadinessService service =
                new ProjectReadinessService(contributors, evaluator, accessCache, projectDao);

        ProjectReadinessResult result = service.getReadiness(PROJECT_ID);

        // Included keys == the keys of the areas whose resource was granted.
        List<String> expectedKeys = AREA_SPECS.stream()
                .filter(spec -> grantedResources.contains(spec.resource()))
                .map(AreaSpec::key)
                .toList();
        List<String> actualKeys = result.areas().stream().map(AreaContribution::key).toList();
        assertThat(actualKeys).isEqualTo(expectedKeys);

        // Nothing about an omitted area leaks: no area with a non-granted resource appears at all.
        List<String> omittedKeys = AREA_SPECS.stream()
                .filter(spec -> !grantedResources.contains(spec.resource()))
                .map(AreaSpec::key)
                .toList();
        if (!omittedKeys.isEmpty()) {
            assertThat(actualKeys).doesNotContainAnyElementsOf(omittedKeys);
        }

        // Headline is computed over the included areas only.
        assertThat(result.overallPercentage())
                .isEqualTo(ReadinessMath.overallPercentage(result.areas()));
        assertThat(result.overallState())
                .isEqualTo(ReadinessMath.overallState(result.areas()));
    }

    // =======================================================================
    // Unit: no readable area → 200 + 0% + BLOCKED (R6.4, R4.4)
    // =======================================================================

    @Test
    @DisplayName("no readable area → 200 + empty areas + 0% + BLOCKED, no crash (R6.4, R4.4)")
    void emptyAreasAreZeroPercentBlocked() {
        authenticateRole("CLIENT");

        List<AreaContribution> source = AREA_SPECS.stream()
                .map(spec -> contribution(spec.key(), ReadinessState.DONE, 100))
                .toList();
        List<ProjectReadinessContributor> contributors = contributorsReturning(source);

        ForemenPermissionEvaluator evaluator = mock(ForemenPermissionEvaluator.class);
        // Deny every area resource.
        for (AreaSpec spec : AREA_SPECS) {
            when(evaluator.isAllowed("CLIENT", spec.resource(), "READ")).thenReturn(false);
        }
        ProjectReadinessService service = new ProjectReadinessService(
                contributors, evaluator, accessibleProjectCache(), projectDaoFound());

        ProjectReadinessResult result = service.getReadiness(PROJECT_ID);

        assertThat(result.projectId()).isEqualTo(PROJECT_ID);
        assertThat(result.areas()).isEmpty();
        assertThat(result.overallPercentage()).isZero();
        assertThat(result.overallState()).isEqualTo(ReadinessState.BLOCKED);
        // No denied contributor was ever asked to contribute.
        contributors.forEach(c -> assertThat(((FakeContributor) c).wasCalled()).isFalse());
    }

    @Test
    @DisplayName("a non-accessible project is 404 error.entity.not.found before loading the row (R3.2)")
    void nonAccessibleProjectIs404() {
        authenticateRole("MANAGER");
        ProjectDao projectDao = mock(ProjectDao.class);
        ProjectAccessCache accessCache = mock(ProjectAccessCache.class);
        when(accessCache.get(USER_ID)).thenReturn(Set.of()); // project not accessible
        ProjectReadinessService service = new ProjectReadinessService(
                contributorsReturning(List.of()), mock(ForemenPermissionEvaluator.class),
                accessCache, projectDao);

        assertThatThrownBy(() -> service.getReadiness(PROJECT_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getMessageCode()).isEqualTo("error.entity.not.found");
                });
        // The access gate short-circuits before even loading the project row.
        verify(projectDao, never()).findById(anyLong());
    }

    @Test
    @DisplayName("a missing project is 404 with the same message code as a non-accessible one (R3.3)")
    void missingProjectIs404() {
        authenticateAdmin(); // ADMIN bypasses accessibility but the row is absent
        ProjectDao projectDao = mock(ProjectDao.class);
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.empty());
        ProjectReadinessService service = new ProjectReadinessService(
                contributorsReturning(List.of()), mock(ForemenPermissionEvaluator.class),
                mock(ProjectAccessCache.class), projectDao);

        assertThatThrownBy(() -> service.getReadiness(PROJECT_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getMessageCode()).isEqualTo("error.entity.not.found");
                });
    }

    // --- service / collaborator builders ------------------------------------

    /** A service whose ADMIN caller reads every area; the project row exists. */
    private ProjectReadinessService adminService(List<ProjectReadinessContributor> contributors) {
        ForemenPermissionEvaluator evaluator = mock(ForemenPermissionEvaluator.class);
        // ADMIN bypass: isAllowed is true for every (resource, READ) — the real evaluator returns
        // true for ADMIN regardless, so stub leniently to the same effect.
        lenient().when(evaluator.isAllowed(eq("ADMIN"), org.mockito.ArgumentMatchers.anyString(), eq("READ")))
                .thenReturn(true);
        return new ProjectReadinessService(
                contributors, evaluator, mock(ProjectAccessCache.class), projectDaoFound());
    }

    private ProjectDao projectDaoFound() {
        ProjectDao projectDao = mock(ProjectDao.class);
        lenient().when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(project()));
        return projectDao;
    }

    private ProjectAccessCache accessibleProjectCache() {
        ProjectAccessCache accessCache = mock(ProjectAccessCache.class);
        lenient().when(accessCache.get(USER_ID)).thenReturn(Set.of(PROJECT_ID));
        return accessCache;
    }

    private static ProjectEntity project() {
        ProjectEntity p = new ProjectEntity();
        p.setId(PROJECT_ID);
        p.setName("Project");
        p.setStatus(ProjectStatus.DRAFT);
        return p;
    }

    /**
     * Builds one {@link FakeContributor} per {@link #AREA_SPECS} entry, each returning the matching
     * contribution from {@code source} (by index). The returned list is in declared order; callers
     * may reorder it to exercise discovery-order independence.
     */
    private static List<ProjectReadinessContributor> contributorsReturning(List<AreaContribution> source) {
        List<ProjectReadinessContributor> contributors = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            AreaSpec spec = AREA_SPECS.get(i);
            contributors.add(new FakeContributor(spec, source.get(i)));
        }
        return contributors;
    }

    private static AreaContribution contribution(String key, ReadinessState state, int percentage) {
        List<OutstandingItem> items = (state == ReadinessState.DONE && percentage == 100)
                ? List.of()
                : List.of(new OutstandingItem("readiness." + key + ".todo", Map.of("count", 1), key));
        return new AreaContribution(key, "workspace.tab." + key, percentage, state, items);
    }

    // --- auth helpers -------------------------------------------------------

    private static void authenticateAdmin() {
        authenticate("1", "ROLE_ADMIN");
    }

    private static void authenticateRole(String roleCode) {
        authenticate(String.valueOf(USER_ID), "ROLE_" + roleCode);
    }

    private static void authenticate(String principal, String authority) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // --- providers ----------------------------------------------------------

    /**
     * A list of exactly six well-formed contributions (one per area), each a random valid
     * state/percentage pairing with the empty-iff-DONE-100 item invariant honored, so the service's
     * handling of real contributor output is exercised across the whole state space.
     */
    @Provide
    Arbitrary<List<AreaContribution>> contributionList() {
        List<Arbitrary<AreaContribution>> perArea = new ArrayList<>();
        for (AreaSpec spec : AREA_SPECS) {
            perArea.add(contributionFor(spec.key()));
        }
        return Combinators.combine(perArea).as(ArrayList::new);
    }

    private static Arbitrary<AreaContribution> contributionFor(String key) {
        Arbitrary<ReadinessState> state = Arbitraries.of(ReadinessState.class);
        return state.flatMap(s -> switch (s) {
            // DONE pairs with 100 (so items are empty); PARTIAL/BLOCKED keep a non-ready percentage.
            case DONE -> Arbitraries.just(contribution(key, ReadinessState.DONE, 100));
            case PARTIAL -> Arbitraries.integers().between(1, 99)
                    .map(p -> contribution(key, ReadinessState.PARTIAL, p));
            case BLOCKED -> Arbitraries.just(contribution(key, ReadinessState.BLOCKED, 0));
        });
    }

    /** Any subset of the six area resources (the granted-READ set for Property 6). */
    @Provide
    Arbitrary<Set<String>> grantedResources() {
        List<String> all = AREA_SPECS.stream().map(AreaSpec::resource).toList();
        return Arbitraries.subsetOf(all).map(java.util.LinkedHashSet::new);
    }

    // --- test doubles -------------------------------------------------------

    /** Declared (areaKey, areaResource, order) for one area. */
    private record AreaSpec(String key, String resource, int order) {}

    /**
     * A controllable {@link ProjectReadinessContributor} returning a fixed contribution and
     * recording whether it was asked to contribute (so a denied area can be asserted never called).
     */
    private static final class FakeContributor implements ProjectReadinessContributor {
        private final AreaSpec spec;
        private final AreaContribution contribution;
        private boolean called;

        private FakeContributor(AreaSpec spec, AreaContribution contribution) {
            this.spec = spec;
            this.contribution = contribution;
        }

        @Override
        public String areaKey() {
            return spec.key();
        }

        @Override
        public String areaResource() {
            return spec.resource();
        }

        @Override
        public int order() {
            return spec.order();
        }

        @Override
        public AreaContribution contribute(Long projectId) {
            this.called = true;
            return contribution;
        }

        boolean wasCalled() {
            return called;
        }
    }
}
