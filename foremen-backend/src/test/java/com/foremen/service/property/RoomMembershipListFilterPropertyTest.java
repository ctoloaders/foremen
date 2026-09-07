package com.foremen.service.property;

import com.foremen.config.security.ProjectAccessProperties;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property test for the project-scoped LIST membership filter of the {@code Room} entity — a
 * project-scoped <em>child</em> of {@code Project} (FOR-04-14).
 *
 * <p>Covers the design property assigned to task 9.5:</p>
 * <ul>
 *   <li><b>Property 8: Non-ADMIN list returns exactly the rooms whose owning project the caller is a
 *       member of</b> &mdash; Validates Requirements 5.3, 7.5.</li>
 * </ul>
 *
 * <p>Unlike the {@code Project} anchor (whose {@link ProjectScopedService#getProjectIdPath()} is the
 * single segment {@code "id"}), a {@code Room} resolves its project boundary through its
 * {@code @ManyToOne project} association, so {@code RoomService.getProjectIdPath()} returns the dotted
 * association path {@code "project.id"}. The inherited {@code addRequiredQuery()} therefore joins the
 * {@code project} association and restricts {@code project.id} to the caller's allowed project set.
 * This test asserts that filter admits, for a non-ADMIN caller, <strong>exactly</strong> the rooms
 * whose owning project the caller is a member of and no room whose project the caller is not a member
 * of.</p>
 *
 * <p>This is a unit-level property that exercises the filtering at the service/specification layer
 * (no Testcontainers boot). It models:</p>
 * <ul>
 *   <li>a whole membership graph as data — a generated {@code Map<userId, Set<projectId>>} over a
 *       shared universe of users and projects; and</li>
 *   <li>a set of rooms scattered across those projects — a generated {@code Map<roomId, projectId>}
 *       — so the property can assert the concrete room result set, not just the project id set.</li>
 * </ul>
 *
 * <p>The pieces are wired as in production: a REAL {@link ProjectAccessCache} (default idle TTL) is
 * self-loaded from a stub {@link ProjectMemberDao} that answers {@code findDistinctProjectIdsByUserId}
 * straight from the generated graph, and a {@code Room}-shaped {@link ProjectScopedService} stub whose
 * {@code getProjectIdPath()} returns {@code "project.id"} and whose {@code allowedProjectIds(userId)}
 * delegates to that cache — exactly what {@code RoomService} does. The property is checked three
 * ways:</p>
 * <ol>
 *   <li><b>Result-set contract.</b> The rooms admitted by the caller's allowed project set (the
 *       rooms whose project is in {@code allowedProjectIds(caller)}) equal exactly the rooms whose
 *       owning project the caller is a member of — every such room is visible and no room in a
 *       non-member project is visible (including the empty-membership case, Requirement 5.3).</li>
 *   <li><b>Allowed-set contract.</b> {@code allowedProjectIds(caller)} equals the caller's
 *       membership row-set from the graph.</li>
 *   <li><b>Filter shape.</b> The {@code addRequiredQuery()} Specification produced for the
 *       authenticated non-ADMIN caller <em>joins</em> the {@code project} association and restricts
 *       the joined {@code id} to exactly the caller's membership set via {@code path.in(callerSet)}
 *       (non-empty), or is an always-false match-nothing predicate when the caller is a member of
 *       zero projects (Requirement 5.3).</li>
 * </ol>
 *
 * <p>ADMIN bypass (returns {@code null} / no filter) is exercised separately in
 * {@code ProjectScopedServiceDecisionPropertyTest} and is out of scope for this non-ADMIN membership
 * property.</p>
 */
// Feature: FOR-04-14-room, Property 8: Non-ADMIN list returns exactly the rooms whose owning project the caller is a member of.
class RoomMembershipListFilterPropertyTest {

    /** Non-ADMIN authorities the LIST filter must membership-scope (never the ADMIN bypass forms). */
    private static final List<String> NON_ADMIN_AUTHORITIES = List.of(
            "ROLE_MANAGER", "ROLE_FOREMAN", "ROLE_WORKER", "ROLE_FINANCIER", "ROLE_CLIENT");

    @AfterTry
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * <b>Property 8 (result-set contract):</b> for any generated membership graph, any generated set
     * of rooms scattered across the projects, and any caller drawn from the graph's user universe,
     * the rooms admitted by the caller's allowed project set equal <strong>exactly</strong> the rooms
     * whose owning project the caller is a member of — every membership-project room is visible, and
     * no room in a project the caller is not a member of is ever visible (empty-membership caller
     * sees zero rooms, Requirement 5.3).
     *
     * <b>Validates: Requirements 5.3, 7.5</b>
     */
    @Property(tries = 100)
    void listReturnsExactlyRoomsInCallerMembershipProjects(
            @ForAll("scenarios") Scenario scenario,
            @ForAll @IntRange(min = 0, max = 20) int callerPick) {

        MembershipGraph graph = scenario.graph();
        long caller = graph.pickUser(callerPick);
        Set<Long> allowedProjects = graph.membershipOf(caller);

        RoomScopedService service = roomService(graph);

        // The allowed project set the production filter would restrict "project.id" to.
        Set<Long> allowed = service.allowedProjectIds(caller);

        // Rooms the filter would admit: those whose owning project is in the allowed set.
        Set<Long> visibleRooms = scenario.rooms().entrySet().stream()
                .filter(e -> allowed.contains(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        // Rooms the caller MUST see: those whose owning project the caller is a member of.
        Set<Long> expectedRooms = scenario.rooms().entrySet().stream()
                .filter(e -> allowedProjects.contains(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        assertThat(visibleRooms)
                .as("caller %d must see exactly the rooms whose owning project it is a member of %s",
                        caller, allowedProjects)
                .containsExactlyInAnyOrderElementsOf(expectedRooms);

        // Cross-check every room individually against the membership relation.
        for (Map.Entry<Long, Long> room : scenario.rooms().entrySet()) {
            long roomId = room.getKey();
            long owningProject = room.getValue();
            boolean callerIsMember = allowedProjects.contains(owningProject);
            assertThat(visibleRooms.contains(roomId))
                    .as("room %d (project %d) visibility for caller %d must equal its membership (%s)",
                            roomId, owningProject, caller, callerIsMember)
                    .isEqualTo(callerIsMember);
        }
    }

    /**
     * <b>Property 8 (allowed-set contract):</b> the room service's {@code allowedProjectIds(caller)}
     * equals exactly the caller's membership row-set from the graph — the project set the LIST filter
     * restricts {@code project.id} to.
     *
     * <b>Validates: Requirements 5.3, 7.5</b>
     */
    @Property(tries = 100)
    void allowedSetEqualsCallerMembershipSet(
            @ForAll("scenarios") Scenario scenario,
            @ForAll @IntRange(min = 0, max = 20) int callerPick) {

        MembershipGraph graph = scenario.graph();
        long caller = graph.pickUser(callerPick);
        Set<Long> expected = graph.membershipOf(caller);

        RoomScopedService service = roomService(graph);

        Set<Long> allowed = service.allowedProjectIds(caller);

        assertThat(allowed)
                .as("caller %d's allowed project set must equal exactly its membership row-set %s",
                        caller, expected)
                .containsExactlyInAnyOrderElementsOf(expected);

        for (Long project : graph.allProjects()) {
            if (!expected.contains(project)) {
                assertThat(allowed)
                        .as("project %d, which caller %d is NOT a member of, must never be allowed",
                                project, caller)
                        .doesNotContain(project);
            }
        }
    }

    /**
     * <b>Property 8 (filter shape):</b> for a generated graph and an authenticated non-ADMIN caller,
     * the LIST filter Specification <em>joins</em> the {@code project} association and restricts the
     * joined {@code id} to exactly the caller's membership set (an {@code IN} over that set), or —
     * when the caller has zero memberships — degrades to an always-false match-nothing predicate so
     * the caller lists no rooms (Requirement 5.3).
     *
     * <b>Validates: Requirements 5.3, 7.5</b>
     */
    @Property(tries = 100)
    void listFilterJoinsProjectAndRestrictsIdToCallerMembershipSet(
            @ForAll("scenarios") Scenario scenario,
            @ForAll @IntRange(min = 0, max = 20) int callerPick,
            @ForAll("nonAdminAuthorities") String authority) {

        MembershipGraph graph = scenario.graph();
        long caller = graph.pickUser(callerPick);
        Set<Long> expected = graph.membershipOf(caller);

        authenticateWith(String.valueOf(caller), authority);
        RoomScopedService service = roomService(graph);

        Specification<Object> spec = service.addRequiredQuery();

        assertThat(spec)
                .as("a non-ADMIN caller's LIST filter must be a non-null Specification")
                .isNotNull();

        InspectedFilter inspected = inspect(spec);

        if (expected.isEmpty()) {
            assertThat(inspected.disjunctionCalled)
                    .as("caller %d with zero memberships must see no rooms (match-nothing)", caller)
                    .isTrue();
            assertThat(inspected.inArgument)
                    .as("a zero-membership caller must produce no IN filter")
                    .isNull();
        } else {
            assertThat(inspected.disjunctionCalled)
                    .as("caller %d with memberships must NOT fall back to match-nothing", caller)
                    .isFalse();
            assertThat(inspected.projectJoined)
                    .as("the filter must JOIN the 'project' association (dotted 'project.id' path)")
                    .isTrue();
            assertThat(inspected.idPathResolved)
                    .as("the filter must resolve the joined 'id' path")
                    .isTrue();
            assertThat(inspected.inArgument)
                    .as("the filter must restrict 'project.id' to exactly caller %d's membership set %s",
                            caller, expected)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    // --- Wiring: production cache + Room-shaped scoped service over the generated graph ---

    /**
     * Builds a {@code Room}-shaped {@link ProjectScopedService} ({@code getProjectIdPath()=="project.id"})
     * whose {@code allowedProjectIds} is served by a REAL {@link ProjectAccessCache} self-loading from
     * a stub {@link ProjectMemberDao} backed by the generated graph — mirroring {@code RoomService}.
     */
    private RoomScopedService roomService(MembershipGraph graph) {
        ProjectMemberDao dao = mock(ProjectMemberDao.class);
        when(dao.findDistinctProjectIdsByUserId(anyLong()))
                .thenAnswer(invocation -> new HashSet<>(graph.membershipOf(invocation.getArgument(0))));
        ProjectAccessCache cache = new ProjectAccessCache(new ProjectAccessProperties(null), dao);
        return new RoomScopedService(cache);
    }

    // --- Specification inspection ---

    /**
     * Invokes {@code spec.toPredicate(root, query, cb)} against mocked criteria objects and records
     * whether {@code cb.disjunction()} was produced (match-nothing), whether the {@code project}
     * association was joined, whether the joined {@code id} path was resolved, and — when an
     * {@code IN} filter was produced — the exact collection passed to {@code path.in(...)}.
     *
     * <p>The dotted {@code "project.id"} path resolves as {@code root.join("project").get("id")}, so
     * the stub returns a mocked {@link Join} from {@code root.join("project")} and a mocked
     * {@link Path} from {@code join.get("id")}, capturing the {@code in(...)} argument on that path.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private InspectedFilter inspect(Specification<Object> spec) {
        Root<Object> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Join<Object, Object> projectJoin = mock(Join.class);
        Path<Object> idPath = mock(Path.class);

        Predicate disjunctionPredicate = mock(Predicate.class);
        Predicate inPredicate = mock(Predicate.class);

        InspectedFilter result = new InspectedFilter();
        AtomicBoolean projectJoined = new AtomicBoolean(false);
        AtomicBoolean idResolved = new AtomicBoolean(false);

        doReturn(disjunctionPredicate).when(cb).disjunction();

        // Dotted path "project.id" -> root.join("project").get("id").
        org.mockito.Mockito.doAnswer(inv -> {
            projectJoined.set(true);
            return projectJoin;
        }).when(root).join("project");

        org.mockito.Mockito.doAnswer(inv -> {
            idResolved.set(true);
            return idPath;
        }).when(projectJoin).get("id");

        // Capture the collection handed to path.in(...).
        org.mockito.Mockito.doAnswer(inv -> {
            result.inArgument = new HashSet<>((java.util.Collection<Long>) inv.getArgument(0));
            return inPredicate;
        }).when(idPath).in(any(java.util.Collection.class));

        Predicate produced = spec.toPredicate(root, query, cb);

        result.disjunctionCalled = (produced == disjunctionPredicate);
        result.projectJoined = projectJoined.get();
        result.idPathResolved = idResolved.get();
        return result;
    }

    private static class InspectedFilter {
        boolean disjunctionCalled;
        boolean projectJoined;
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

    // --- Room-shaped scoped service stub ---

    /**
     * A minimal {@link ProjectScopedService} shaped like {@code RoomService}: its
     * {@code getProjectIdPath()} returns {@code "project.id"} (the dotted association path of a
     * project-scoped child) and its {@code allowedProjectIds} delegates to the real
     * {@link ProjectAccessCache}. Only the membership-filter decision logic is exercised, so the CRUD
     * plumbing is no-op.
     */
    static class RoomScopedService
            implements ProjectScopedService<Object, Object, Object, Object> {

        private final ProjectAccessCache cache;

        RoomScopedService(ProjectAccessCache cache) {
            this.cache = cache;
        }

        @Override
        public String getProjectIdPath() {
            return "project.id"; // the Room is a project-scoped child -> dotted association path.
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

    // --- Scenario (graph + rooms) model + generators ---

    /**
     * A generated scenario: a membership graph over users and projects, plus a set of rooms scattered
     * across those projects ({@code roomId -> owning projectId}).
     */
    record Scenario(MembershipGraph graph, Map<Long, Long> rooms) {
    }

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

        List<Long> projects() {
            return projects;
        }

        Set<Long> allProjects() {
            return new HashSet<>(projects);
        }
    }

    /**
     * Generates scenarios: a membership graph (1..5 users ids 1..5, 0..8 projects ids 1000..1007, a
     * random membership relation) plus 0..12 rooms (ids 5000..) each assigned to one of the graph's
     * projects — so empty-membership callers, users with overlapping/disjoint sets, projects with no
     * rooms, and rooms in non-member projects are all exercised. When the graph has no projects the
     * scenario has no rooms (a room must belong to a project).
     */
    @Provide
    Arbitrary<Scenario> scenarios() {
        return membershipGraphs().flatMap(graph -> roomsFor(graph).map(rooms -> new Scenario(graph, rooms)));
    }

    /**
     * Builds an arbitrary {@code roomId -> projectId} map placing 0..12 rooms across the graph's
     * projects. Empty when the graph has no projects.
     */
    private Arbitrary<Map<Long, Long>> roomsFor(MembershipGraph graph) {
        List<Long> projects = graph.projects();
        if (projects.isEmpty()) {
            return Arbitraries.just(new HashMap<>());
        }
        return Arbitraries.of(projects.toArray(new Long[0]))
                .list().ofMinSize(0).ofMaxSize(12)
                .map(assignedProjects -> {
                    Map<Long, Long> rooms = new HashMap<>();
                    long roomId = 5000L;
                    for (Long projectId : assignedProjects) {
                        rooms.put(roomId++, projectId);
                    }
                    return rooms;
                });
    }

    @Provide
    Arbitrary<MembershipGraph> membershipGraphs() {
        Arbitrary<List<Long>> userLists = Arbitraries.longs().between(1L, 5L)
                .set().ofMinSize(1).ofMaxSize(5)
                .map(ArrayList::new);
        Arbitrary<Set<Long>> projectSets = Arbitraries.longs().between(1000L, 1007L)
                .set().ofMinSize(0).ofMaxSize(8);

        return Combinators.combine(userLists, projectSets).flatAs((users, projectSet) -> {
            List<Long> projects = new ArrayList<>(projectSet);
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
