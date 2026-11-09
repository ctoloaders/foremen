package com.foremen.service.dashboard;

import com.foremen.config.security.PermissionProperties;
import com.foremen.config.security.ProjectAccessProperties;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.permission.PermissionCache;
import com.foremen.service.permission.PermissionSet;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit and property tests for {@link DashboardAccessService} and {@link PermissionSet#hasAnyOperation}.
 * <b>Validates: Requirements 6.3, 8.1, 8.2, 9.1, 9.3, 9.5</b>
 */
class DashboardAccessServiceTest {

    private static final String[] OPS = {"CREATE", "READ", "UPDATE", "DELETE"};

    private final ProjectDao projectDao = mock(ProjectDao.class);
    private final ProjectMemberDao memberDao = mock(ProjectMemberDao.class);
    private final RoleDao roleDao = mock(RoleDao.class);
    private final PermissionCache cache = new PermissionCache(new PermissionProperties(60));
    private final DashboardAccessService service = new DashboardAccessService(
            new ForemenPermissionEvaluator(roleDao, cache), cache,
            new ProjectAccessCache(new ProjectAccessProperties(60), memberDao), projectDao);

    private void role(String code, String... grants) {
        cache.get(code, c -> new PermissionSet(new HashSet<>(Set.of(grants))));
    }

    @Test
    void hasAnyOperationMatchesPrefixExactly() {
        PermissionSet set = new PermissionSet(Set.of("PAYMENTS:READ", "PAYMENTS_X:CREATE"));
        assertThat(set.hasAnyOperation("PAYMENTS")).isTrue();
        assertThat(set.hasAnyOperation("PAYMENTS_X")).isTrue();
        assertThat(set.hasAnyOperation("PAYMENT")).isFalse();
        assertThat(set.hasAnyOperation("USERS")).isFalse();
        assertThat(PermissionSet.empty().hasAnyOperation("PAYMENTS")).isFalse();
    }

    @Test
    void missingResourcesReportsOnlyUngrantedOnes() {
        role("MANAGER", "PROJECTS:READ", "PAYMENTS:UPDATE");
        assertThat(service.missingResources("MANAGER", Set.of("PROJECTS", "PAYMENTS", "PAYROLL")))
                .containsExactly("PAYROLL");
    }

    @Test
    void adminHasNoMissingResources() {
        assertThat(service.missingResources("ADMIN", Set.of("PAYROLL", "X"))).isEmpty();
    }

    @Test
    void unknownRoleMissesEverything() {
        when(roleDao.findByCode("GHOST")).thenReturn(Optional.empty());
        assertThat(service.missingResources("GHOST", Set.of("PROJECTS"))).containsExactly("PROJECTS");
        assertThat(service.evaluate(1L, "GHOST", true, Set.of("PROJECTS"), false, null))
                .isEqualTo(WidgetState.NO_ACCESS);
    }

    @Test
    void evaluateOrder() {
        role("MANAGER", "PROJECTS:READ");
        when(memberDao.findDistinctProjectIdsByUserId(5L)).thenReturn(Set.of(10L));
        Set<String> req = Set.of("PROJECTS");
        assertThat(service.evaluate(5L, "MANAGER", false, Set.of("PAYROLL"), false, null))
                .isEqualTo(WidgetState.INACTIVE);
        assertThat(service.evaluate(5L, "MANAGER", true, Set.of("PAYROLL"), true, 10L))
                .isEqualTo(WidgetState.NO_ACCESS);
        assertThat(service.evaluate(5L, "MANAGER", true, req, true, 99L))
                .isEqualTo(WidgetState.PROJECT_UNAVAILABLE);
        assertThat(service.evaluate(5L, "MANAGER", true, req, true, null))
                .isEqualTo(WidgetState.PROJECT_UNAVAILABLE);
        assertThat(service.evaluate(5L, "MANAGER", true, req, true, 10L))
                .isEqualTo(WidgetState.AVAILABLE);
        assertThat(service.evaluate(5L, "MANAGER", true, req, false, null))
                .isEqualTo(WidgetState.AVAILABLE);
    }

    @Test
    void adminProjectWidgetDependsOnProjectExistence() {
        when(projectDao.existsById(1L)).thenReturn(true);
        when(projectDao.existsById(2L)).thenReturn(false);
        assertThat(service.evaluate(1L, "ADMIN", true, Set.of("PAYROLL"), true, 1L))
                .isEqualTo(WidgetState.AVAILABLE);
        assertThat(service.evaluate(1L, "ADMIN", true, Set.of("PAYROLL"), true, 2L))
                .isEqualTo(WidgetState.PROJECT_UNAVAILABLE);
    }

    // Property: hasAnyOperation is true iff some grant key has that resource.
    @Property(tries = 100)
    void hasAnyOperationEqualsExistence(@ForAll("grants") Set<String> grants,
                                        @ForAll("resource") String resource) {
        boolean expected = grants.stream().anyMatch(g -> g.startsWith(resource + ":"));
        assertThat(new PermissionSet(grants).hasAnyOperation(resource)).isEqualTo(expected);
    }

    // Property: monotonicity - adding grants never increases the missing set.
    @Property(tries = 100)
    void missingResourcesIsMonotonic(@ForAll("grants") Set<String> base,
                                     @ForAll("grants") Set<String> extra,
                                     @ForAll("required") Set<String> required) {
        String small = "R_SMALL_" + System.nanoTime();
        String big = "R_BIG_" + System.nanoTime();
        cache.get(small, c -> new PermissionSet(base));
        Set<String> union = new HashSet<>(base);
        union.addAll(extra);
        cache.get(big, c -> new PermissionSet(union));
        assertThat(service.missingResources(small, required))
                .containsAll(service.missingResources(big, required));
    }

    // Property: ADMIN never misses; an unknown role (empty set) misses every required resource.
    @Property(tries = 50)
    void adminEmptyAndUnknownRoleDenies(@ForAll("required") Set<String> required) {
        assertThat(service.missingResources("ADMIN", required)).isEmpty();
        String unknown = "UNKNOWN_" + System.nanoTime();
        when(roleDao.findByCode(unknown)).thenReturn(Optional.empty());
        assertThat(service.missingResources(unknown, required)).containsExactlyInAnyOrderElementsOf(required);
    }

    @Provide
    Arbitrary<String> resource() {
        return Arbitraries.of("PROJECTS", "PAYMENTS", "PAYROLL", "USERS", "ROOMS");
    }

    @Provide
    Arbitrary<Set<String>> required() {
        return resource().set().ofMinSize(1).ofMaxSize(4);
    }

    @Provide
    Arbitrary<Set<String>> grants() {
        return Combinators.combine(resource(), Arbitraries.of(OPS)).as((r, o) -> r + ":" + o)
                .set().ofMaxSize(8);
    }
}
