package com.foremen.service.property;

import com.foremen.config.security.ProjectAccessProperties;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterTry;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property test for the project-scoped LIST membership filter of the {@code Project} anchor.
 *
 * <p>Covers the design property assigned to task 7.3:</p>
 * <ul>
 *   <li><b>Property 6: Non-ADMIN list returns exactly the caller's membership set</b>
 *       &mdash; Validates Requirements 4.2, 4.4.</li>
 * </ul>
 *
 * <p>This is a unit-level property. It models a whole membership graph as data — a generated
 * {@code Map<userId, Set<projectId>>} over a shared universe of users and projects — and asserts
 * that the project-scoped access filter of the {@code Project} anchor (whose
 * {@link ProjectScopedService#getProjectIdPath()} is {@code "id"}) admits, for a non-ADMIN caller,
 * <strong>exactly</strong> the set of projects in which that caller is a member and no project in
 * which the caller is not a member.</p>
 *
 * <p>The pieces are wired as in production: a REAL {@link ProjectAccessCache} (default idle TTL) is
 * self-loaded from a stub {@link ProjectMemberDao} that answers {@code findDistinctProjectIdsByUserId}
 * straight from the generated graph, and an anchor-shaped {@link ProjectScopedService} stub whose
 * {@code getProjectIdPath()} returns {@code "id"} and whose {@code allowedProjectIds(userId)}
 * delegates to that cache — exactly what {@code ProjectService} does. The property is checked two
 * ways:</p>
 * <ol>
 *   <li><b>Allowed-set contract.</b> {@code allowedProjectIds(caller)} equals the caller's
 *       membership row-set from the graph, contains every project the caller belongs to, and
 *       contains no project the caller does not belong to (including the empty-membership case,
 *       Requirement 4.4).</li>
 *   <li><b>Filter shape.</b> The {@code addRequiredQuery()} Specification produced for the
 *       authenticated non-ADMIN caller restricts the anchor {@code id} path to exactly the caller's
 *       membership set via {@code path.in(callerSet)} (non-empty), or is an always-false
 *       match-nothing predicate when the caller is a member of zero projects (Requirement 4.4).</li>
 * </ol>
 *
 * <p>ADMIN bypass is exercised separately in {@code ProjectScopedServiceDecisionPropertyTest}
 * (returns {@code null} / no filter) and is out of scope for this unit property, which is about the
 * non-ADMIN membership set.</p>
 */
// Feature: FOR-04-13-project, Property 6: Non-ADMIN list returns exactly the caller's membership set.
class ProjectMembershipListFilterPropertyTest {

    /** Non-ADMIN authorities the LIST filter must membership-scope (never the ADMIN bypass forms). */
    private static final List<String> NON_ADMIN_AUTHORITIES = List.of(
            "ROLE_MANAGER", "ROLE_FOREMAN", "ROLE_WORKER", "ROLE_FINANCIER", "ROLE_CLIENT");

    @AfterTry
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * <b>Property 6 (allowed-set contract):</b> for any generated membership graph and any caller
     * drawn from that graph's user universe, the anchor service's {@code allowedProjectIds(caller)}
     * equals exactly the caller's membership row-set — every project the caller belongs to is
     * present, and no project the caller does not belong to is present (Requirements 4.2, 4.4).
     *
     * <b>Validates: Requirements 4.2, 4.4</b>
     */
    @Property(tries = 100)
    void allowedSetEqualsCallerMembershipSet(
            @ForAll("membershipGraphs") MembershipGraph graph,
            @ForAll @IntRange(min = 0, max = 20) int callerPick) {

        long caller = graph.pickUser(callerPick);
        Set<Long> expected = graph.membershipOf(caller);

        AnchorScopedService service = anchorService(graph);

        Set<Long> allowed = service.allowedProjectIds(caller);

        assertThat(allowed)
                .as("caller %d's allowed set must equal exactly its membership row-set %s",
                        caller, expected)
                .containsExactlyInAnyOrderElementsOf(expected);

        // Every membership project is admitted...
        assertThat(allowed)
                .as("every project caller %d is a member of must be listable", caller)
                .containsAll(expected);

        // ...and no non-member project is ever admitted.
        for (Long project : graph.allProjects()) {
            if (!expected.contains(project)) {
                assertThat(allowed)
                        .as("project %d, which caller %d is NOT a member of, must never be listable",
                                project, caller)
                        .doesNotContain(project);
            }
        }
    }

    /**
     * <b>Property 6 (filter shape):</b> for a generated graph and an authenticated non-ADMIN caller,
     * the LIST filter Specification restricts the anchor {@code id} path to exactly the caller's
     * membership set (an {@code IN} over that set), or — when the caller has zero memberships —
     * degrades to an always-false match-nothing predicate so the caller lists no projects
     * (Requirement 4.4).
     *
     * <b>Validates: Requirements 4.2, 4.4</b>
     */
    @Property(tries = 100)
    void listFilterRestrictsIdPathToCallerMembershipSet(
            @ForAll("membershipGraphs") MembershipGraph graph,
            @ForAll @IntRange(min = 0, max = 20) int callerPick,
            @ForAll("nonAdminAuthorities") String authority) {

        long caller = graph.pickUser(callerPick);
        Set<Long> expected = graph.membershipOf(caller);

        authenticateWith(String.valueOf(caller), authority);
        AnchorScopedService service = anchorService(graph);

        Specification<Object> spec = service.addRequiredQuery();

        assertThat(spec)
                .as("a non-ADMIN caller's LIST filter must be a non-null Specification")
                .isNotNull();

        InspectedFilter inspected = inspect(spec);

        if (expected.isEmpty()) {
            assertThat(inspected.disjunctionCalled)
                    .as("caller %d with zero memberships must see no projects (match-nothing)", caller)
                    .isTrue();
            assertThat(inspected.inArgument)
                    .as("a zero-membership caller must produce no IN filter")
                    .isNull();
        } else {
            assertThat(inspected.disjunctionCalled)
                    .as("caller %d with memberships must NOT fall back to match-nothing", caller)
                    .isFalse();
            assertThat(inspected.idPathResolved)
                    .as("the filter must resolve the anchor 'id' path")
                    .isTrue();
            assertThat(inspected.inArgument)
                    .as("the filter must restrict 'id' to exactly caller %d's membership set %s",
                            caller, expected)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    // --- Wiring: production cache + anchor-shaped scoped service over the generated graph ---

    /**
     * Builds an anchor-shaped {@link ProjectScopedService} ({@code getProjectIdPath()=="id"}) whose
     * {@code allowedProjectIds} is served by a REAL {@link ProjectAccessCache} self-loading from a
     * stub {@link ProjectMemberDao} backed by the generated graph — mirroring {@code ProjectService}.
     */
    private AnchorScopedService anchorService(MembershipGraph graph) {
        ProjectMemberDao dao = mock(ProjectMemberDao.class);
        when(dao.findDistinctProjectIdsByUserId(anyLong()))
                .thenAnswer(invocation -> new HashSet<>(graph.membershipOf(invocation.getArgument(0))));
        ProjectAccessCache cache = new ProjectAccessCache(new ProjectAccessProperties(null), dao);
        return new AnchorScopedService(cache);
    }

    // --- Specification inspection ---

    /**
     * Invokes {@code spec.toPredicate(root, query, cb)} against mocked criteria objects and records
     * whether {@code cb.disjunction()} was produced (match-nothing), whether the {@code "id"} path
     * was resolved, and — when an {@code IN} filter was produced — the exact collection passed to
     * {@code path.in(...)}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private InspectedFilter inspect(Specification<Object> spec) {
        Root<Object> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> idPath = mock(Path.class);

        Predicate disjunctionPredicate = mock(Predicate.class);
        Predicate inPredicate = mock(Predicate.class);

        InspectedFilter result = new InspectedFilter();
        AtomicBoolean idResolved = new AtomicBoolean(false);

        doReturn(disjunctionPredicate).when(cb).disjunction();
        // Anchor path is the single segment "id"; flip the resolved flag when it is read.
        doReturn(idPath).when(root).get(any(String.class));
        doReturn(idPath).when(root).get("id");
        doReturn(idPath).when(root).get("id"); // idempotent; kept explicit for readability
        // Capture the collection handed to path.in(...).
        doReturn(inPredicate).when(idPath).in(any(java.util.Collection.class));

        // Record resolution/argument via a callback stub layered over the return stubs above.
        org.mockito.Mockito.doAnswer(inv -> {
            idResolved.set(true);
            return idPath;
        }).when(root).get("id");
        org.mockito.Mockito.doAnswer(inv -> {
            result.inArgument = new HashSet<>((java.util.Collection<Long>) inv.getArgument(0));
            return inPredicate;
        }).when(idPath).in(any(java.util.Collection.class));

        Predicate produced = spec.toPredicate(root, query, cb);

        result.disjunctionCalled = (produced == disjunctionPredicate);
        result.idPathResolved = idResolved.get();
        return result;
    }

    private static class InspectedFilter {
        boolean disjunctionCalled;
        boolean idPathResolved;
        Set<Long> inArgument;
    }

    // --- SecurityContext helper ---

    private void authenticateWith(String principalName, String authority) {
        SecurityContextHolder.clearContext();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(authority));
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(principalName, "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // --- Anchor-shaped scoped service stub ---

    /**
     * A minimal {@link ProjectScopedService} shaped like {@code ProjectService}: its
     * {@code getProjectIdPath()} returns {@code "id"} (the anchor's own id) and its
     * {@code allowedProjectIds} delegates to the real {@link ProjectAccessCache}. Only the
     * membership-filter decision logic is exercised, so the CRUD plumbing is no-op.
     */
    static class AnchorScopedService
            implements ProjectScopedService<Object, Object, Object, Object> {

        private final ProjectAccessCache cache;

        AnchorScopedService(ProjectAccessCache cache) {
            this.cache = cache;
        }

        @Override
        public String getProjectIdPath() {
            return "id"; // the Project is the anchor -> its own id.
        }

        @Override
        public Set<Long> allowedProjectIds(Long userId) {
            return cache.get(userId);
        }

        @Override
        public com.foremen.mapper.ServiceToDaoMapper<Object, Object, Object> getMapper() {
            return null;
        }

        @Override
        public com.foremen.dao.AdminDao<Object, Object> getDao() {
            return null;
        }

        @Override
        public com.foremen.service.audit.AuditLogDao getAuditLogDao() {
            return null;
        }

        @Override
        public jakarta.persistence.EntityManager getEntityManager() {
            return null;
        }

        @Override
        public Class<Object> getDaoModelClass() {
            return Object.class;
        }
    }

    // --- Membership graph model + generators ---

    /**
     * A membership graph over a fixed universe of user ids and project ids, plus a
     * {@code userId -> Set<projectId>} membership relation. Users and projects are drawn from
     * disjoint id ranges so a project id is never mistaken for a user id.
     */
    static final class MembershipGraph {
        private final List<Long> users;
        private final List<Long> projects;
        private final Map<Long, Set<Long>> memberships;

        MembershipGraph(List<Long> users, List<Long> projects, Map<Long, Set<Long>> memberships) {
            this.users = users;
            this.projects = projects;
            this.memberships = memberships;
        }

        /** Deterministically picks one of the graph's users for the given index. */
        long pickUser(int index) {
            return users.get(Math.floorMod(index, users.size()));
        }

        /** The caller's membership row-set (empty when the caller belongs to no project). */
        Set<Long> membershipOf(long userId) {
            return memberships.getOrDefault(userId, Set.of());
        }

        Set<Long> allProjects() {
            return new HashSet<>(projects);
        }
    }

    /**
     * Generates membership graphs: 1..5 users (ids 1..5), 0..8 projects (ids 1000..1007), and a
     * random membership relation assigning each user a (possibly empty) subset of the projects — so
     * the empty-membership caller (Requirement 4.4) and users with overlapping/disjoint sets are
     * all exercised.
     */
    @Provide
    Arbitrary<MembershipGraph> membershipGraphs() {
        Arbitrary<List<Long>> userLists = Arbitraries.longs().between(1L, 5L)
                .set().ofMinSize(1).ofMaxSize(5)
                .map(java.util.ArrayList::new);
        Arbitrary<Set<Long>> projectSets = Arbitraries.longs().between(1000L, 1007L)
                .set().ofMinSize(0).ofMaxSize(8);

        return Combinators.combine(userLists, projectSets).flatAs((users, projectSet) -> {
            List<Long> projects = new java.util.ArrayList<>(projectSet);
            // For each user, an arbitrary subset of the available projects.
            Arbitrary<Map<Long, Set<Long>>> membershipArb = subsetPerUser(users, projects);
            return membershipArb.map(m -> new MembershipGraph(users, projects, m));
        });
    }

    /**
     * Builds, for a fixed user list, an arbitrary {@code userId -> subset-of-projects} map by
     * generating one independent membership bit per (user, project) pair.
     */
    private Arbitrary<Map<Long, Set<Long>>> subsetPerUser(List<Long> users, List<Long> projects) {
        int pairCount = users.size() * projects.size();
        if (pairCount == 0) {
            // No projects: every user maps to an empty membership set.
            Map<Long, Set<Long>> empty = new HashMap<>();
            for (Long u : users) {
                empty.put(u, Set.of());
            }
            return Arbitraries.just(empty);
        }
        return Arbitraries.integers().between(0, 1).list().ofSize(pairCount).map(bits -> {
            Map<Long, Set<Long>> map = new HashMap<>();
            int i = 0;
            for (Long u : users) {
                Set<Long> owned = new HashSet<>();
                for (Long p : projects) {
                    if (bits.get(i++) == 1) {
                        owned.add(p);
                    }
                }
                map.put(u, owned);
            }
            return map;
        });
    }

    /** Non-ADMIN authorities: the LIST filter must membership-scope each of them. */
    @Provide
    Arbitrary<String> nonAdminAuthorities() {
        return Arbitraries.of(NON_ADMIN_AUTHORITIES.toArray(new String[0]));
    }
}
