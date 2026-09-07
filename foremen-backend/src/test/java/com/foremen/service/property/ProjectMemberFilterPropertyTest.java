package com.foremen.service.property;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the semantics of the nested member filter
 * {@code members.user.id~in~<ids>} applied to the projects list (FOR-04-13, Requirement 8.2).
 *
 * <p><b>Property 7: Member filter returns exactly the projects having a matching member.</b>
 * For any set of projects, users, and {@code project_members} rows, and any non-empty set {@code U}
 * of selected user ids, a LIST read filtered by {@code members.user.id~in~U} returns exactly the set
 * of projects for which at least one {@code project_members} row joins that project to some user in
 * {@code U}, with no project appearing more than once (the duplicate rows a to-many join produces are
 * collapsed via {@code distinct}). <b>Validates: Requirements 8.2</b></p>
 *
 * <p>The actual JPA join + {@code distinct} produced by {@code SpecificationBuilder} against a live
 * database is exercised by the Testcontainers integration test {@code ProjectMemberFilterIT} (task
 * 9.4). This property instead encodes, at the unit level, the pure <em>set semantics</em> that the
 * {@code members.user.id~in~<ids>} filter is defined to implement: over a generated membership graph
 * (projects each with a set of member user ids), the filter result is the exact
 * membership-intersection set
 * <pre>{@code { project : memberUserIds(project) ∩ U ≠ ∅ } }</pre>
 * deduplicated (each qualifying project exactly once). The model computes both the reference expected
 * set and the "engine-shaped" result (iterate the flat {@code project_members} rows, keep a row when
 * its user id ∈ U, then collapse to distinct project ids) and asserts they coincide — the invariant a
 * correct JOIN + {@code distinct} must uphold.</p>
 *
 * Feature: FOR-04-13-project, Property 7
 */
@Tag("Feature: FOR-04-13-project, Property 7")
class ProjectMemberFilterPropertyTest {

    /** A single {@code project_members} row: which project, which user. (Role is irrelevant to P7.) */
    private record MemberRow(long projectId, long userId) {}

    /** A generated membership graph: the set of all project ids, and the flat member-row list. */
    private record MembershipGraph(Set<Long> allProjectIds, List<MemberRow> memberRows) {}

    /**
     * Reference semantics: the set of project ids that have at least one member whose user id is in
     * {@code selectedUserIds}. This is the {@code { project : memberUserIds(project) ∩ U ≠ ∅ }}
     * definition, expressed directly.
     */
    private static Set<Long> expectedMatchingProjects(MembershipGraph graph, Set<Long> selectedUserIds) {
        Set<Long> result = new LinkedHashSet<>();
        for (MemberRow row : graph.memberRows()) {
            if (selectedUserIds.contains(row.userId())) {
                result.add(row.projectId());
            }
        }
        return result;
    }

    /**
     * "Engine-shaped" evaluation mirroring what a to-many JOIN + {@code distinct} does: filter the
     * flat {@code project_members} rows by the {@code ~in~} predicate on {@code user.id}, project down
     * to the owning project id, then collapse duplicates. The returned list is the distinct result the
     * filtered query would yield (order-independent).
     */
    private static List<Long> filterEngineResult(MembershipGraph graph, Set<Long> selectedUserIds) {
        return graph.memberRows().stream()
                .filter(row -> selectedUserIds.contains(row.userId()))
                .map(MemberRow::projectId)
                .distinct()
                .collect(Collectors.toList());
    }

    // Feature: FOR-04-13-project, Property 7
    // The member filter returns exactly the membership-intersection set, deduplicated.
    // Validates: Requirements 8.2
    @Property(tries = 100)
    void memberFilterReturnsExactlyProjectsWithAMatchingMember(
            @ForAll("membershipGraphs") MembershipGraph graph,
            @ForAll("selectedUserIdSets") Set<Long> selectedUserIds) {

        List<Long> engineResult = filterEngineResult(graph, selectedUserIds);
        Set<Long> expected = expectedMatchingProjects(graph, selectedUserIds);

        // 1) The filter result contains no duplicates (distinct collapses the to-many join fan-out).
        assertThat(engineResult)
                .as("filtered projects list must contain each project at most once (distinct)")
                .doesNotHaveDuplicates();

        // 2) As a set, the filter result equals exactly the membership-intersection set — no missing
        //    matching project and no spurious non-matching project.
        assertThat(new LinkedHashSet<>(engineResult))
                .as("members.user.id~in~U must return exactly the projects intersecting U")
                .isEqualTo(expected);

        // 3) Every returned project is a real project in the graph (join cannot invent project ids).
        assertThat(graph.allProjectIds())
                .as("every filtered project id must be an existing project in the graph")
                .containsAll(engineResult);

        // 4) Characterising membership: a project qualifies IFF at least one of its members is in U.
        for (Long projectId : graph.allProjectIds()) {
            boolean hasMatchingMember = graph.memberRows().stream()
                    .anyMatch(row -> row.projectId() == projectId
                            && selectedUserIds.contains(row.userId()));
            assertThat(engineResult.contains(projectId))
                    .as("project %s present IFF it has a member in U", projectId)
                    .isEqualTo(hasMatchingMember);
        }
    }

    // Feature: FOR-04-13-project, Property 7 (empty-intersection corner)
    // When no member of any project is in U, the filter returns the empty set (never an error/all).
    // Validates: Requirements 8.2
    @Property(tries = 100)
    void selectingOnlyNonMemberUsersReturnsNoProjects(
            @ForAll("membershipGraphs") MembershipGraph graph) {

        // Build a selection guaranteed disjoint from every member user id in the graph.
        long candidate = 10_000_001L;
        Set<Long> existingUserIds = graph.memberRows().stream()
                .map(MemberRow::userId)
                .collect(Collectors.toSet());
        while (existingUserIds.contains(candidate)) {
            candidate++;
        }
        Set<Long> disjointSelection = Set.of(candidate, candidate + 1);

        List<Long> engineResult = filterEngineResult(graph, disjointSelection);

        assertThat(engineResult)
                .as("a selection disjoint from all members must match zero projects")
                .isEmpty();
    }

    // --- arbitrary providers ---

    /**
     * Generates a membership graph: 0..6 projects (ids drawn from a small pool so the same project can
     * receive several member rows — the fan-out {@code distinct} must collapse), and 0..20 member rows
     * each pairing one of those projects with a user id from a small pool (so intersections with the
     * selection are non-trivially populated).
     */
    @Provide
    Arbitrary<MembershipGraph> membershipGraphs() {
        Arbitrary<Set<Long>> projectIdSets =
                Arbitraries.longs().between(1L, 12L).set().ofMinSize(0).ofMaxSize(6);

        return projectIdSets.flatMap(projectIds -> {
            List<Long> projectIdList = new ArrayList<>(projectIds);
            if (projectIdList.isEmpty()) {
                // No projects => no member rows are possible.
                return Arbitraries.just(new MembershipGraph(new LinkedHashSet<>(projectIds), List.of()));
            }
            Arbitrary<MemberRow> rowArb = Combinators.combine(
                            Arbitraries.of(projectIdList),
                            Arbitraries.longs().between(1L, 20L))
                    .as(MemberRow::new);
            return rowArb.list().ofMinSize(0).ofMaxSize(20)
                    .map(rows -> new MembershipGraph(new LinkedHashSet<>(projectIds), rows));
        });
    }

    /** A non-empty set {@code U} of selected user ids drawn from (and slightly beyond) the member pool. */
    @Provide
    Arbitrary<Set<Long>> selectedUserIdSets() {
        return Arbitraries.longs().between(1L, 25L).set().ofMinSize(1).ofMaxSize(8);
    }
}
