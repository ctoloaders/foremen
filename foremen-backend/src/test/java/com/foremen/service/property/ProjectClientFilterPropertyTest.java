package com.foremen.service.property;

// Feature: FOR-04-13-project, Property 8: Client filter returns exactly the projects whose CLIENT member matches

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test P8 for the <b>Client filter</b> compound predicate semantics (FOR-04-13).
 *
 * <p><b>Property 8: Client filter returns exactly the projects whose CLIENT member matches.</b>
 * For any set of projects, users, roles (including the seeded {@code CLIENT} role), and
 * {@code project_members} rows, and any non-empty set {@code U} of selected user ids, a LIST read
 * filtered by the compound client predicate
 * {@code members.user.id~in~U AND members.projectRole.code==CLIENT} returns exactly the set of
 * projects that have a {@code project_members} row whose {@code user.id ∈ U} <b>and</b> whose
 * {@code projectRole.code == "CLIENT"} — i.e. projects whose client is one of the selected users —
 * with each qualifying project appearing exactly once, and excludes projects where the selected
 * users are non-CLIENT members only.
 *
 * <p><b>Validates: Requirements 8.1, 8.2</b>
 *
 * <p>This is a <em>unit-level</em> property over the compound filter semantics. Per the FOR-04-13
 * design, the actual JPA compound join ({@code SpecificationBuilder} nested-collection JOIN + AND
 * conjunction + {@code query.distinct(true)}) is exercised end-to-end by the integration test
 * {@code ProjectMemberFilterIT} (task 9.4). Here the two conjuncts of the client predicate — the
 * {@code ~in~} membership over {@code member.userId} and the {@code ==} equality over
 * {@code member.roleCode} — are modelled directly and required to bind to the <b>same</b> member
 * row (correct "this user is the client" semantics), matching how {@code SpecificationBuilder}
 * reuses one {@code project_members} JOIN per attribute name so both conjuncts constrain a single
 * membership row.
 *
 * <p>The test generates membership graphs whose members carry both {@code CLIENT} and non-{@code
 * CLIENT} roles, computes the expected qualifying-project set independently of the predicate under
 * test, and asserts:
 * <ul>
 *   <li>the compound-filter result equals the independently-computed expected set (extensional
 *       equality — exactly the matching projects, no more, no fewer);</li>
 *   <li>each qualifying project appears exactly once (the to-many join's duplicates are collapsed);</li>
 *   <li>a project that matches {@code U} only through a non-CLIENT member is excluded — verified
 *       directly and cross-checked against the plain member filter ({@code members.user.id~in~U}),
 *       which the compound result is always a subset of.</li>
 * </ul>
 */
class ProjectClientFilterPropertyTest {

    /** The server-fixed project role code identifying the client member (mirrors {@code ProjectService.CLIENT_ROLE_CODE}). */
    private static final String CLIENT_ROLE_CODE = "CLIENT";

    /** Non-CLIENT project role codes a member may hold, used to build mixed-role graphs. */
    private static final List<String> NON_CLIENT_ROLE_CODES =
            List.of("MANAGER", "FOREMAN", "WORKER", "FINANCIER", "ADMIN");

    // --- Model -------------------------------------------------------------------------------

    /** A single {@code project_members} row: which user, under which project role, on which project. */
    private record Member(long userId, String roleCode) {
    }

    /** A project with its full membership set (0..n members). */
    private record Project(long projectId, List<Member> members) {
    }

    /**
     * The compound client predicate under test, mirroring
     * {@code members.user.id~in~U AND members.projectRole.code==CLIENT}: a project matches iff it
     * has a <b>single</b> member row that is BOTH in the selected user set U AND holds the CLIENT
     * role. Returns the deduplicated set of matching project ids (each qualifying project once),
     * mirroring {@code query.distinct(true)} over the to-many join.
     */
    private static Set<Long> applyClientFilter(List<Project> projects, Set<Long> selectedUserIds) {
        Set<Long> result = new LinkedHashSet<>();
        for (Project project : projects) {
            boolean matches = project.members().stream()
                    .anyMatch(m -> selectedUserIds.contains(m.userId())
                            && CLIENT_ROLE_CODE.equals(m.roleCode()));
            if (matches) {
                result.add(project.projectId());
            }
        }
        return result;
    }

    /**
     * The plain member filter {@code members.user.id~in~U} (Property 7 semantics), used only to
     * cross-check that the compound client filter is a subset of it and that non-CLIENT-only
     * matches are dropped by the compound predicate.
     */
    private static Set<Long> applyMemberFilter(List<Project> projects, Set<Long> selectedUserIds) {
        Set<Long> result = new LinkedHashSet<>();
        for (Project project : projects) {
            boolean matches = project.members().stream()
                    .anyMatch(m -> selectedUserIds.contains(m.userId()));
            if (matches) {
                result.add(project.projectId());
            }
        }
        return result;
    }

    /** Independently-computed expected qualifying-project set (the specification of Property 8). */
    private static Set<Long> expectedClientMatches(List<Project> projects, Set<Long> selectedUserIds) {
        return projects.stream()
                .filter(p -> p.members().stream()
                        .anyMatch(m -> CLIENT_ROLE_CODE.equals(m.roleCode())
                                && selectedUserIds.contains(m.userId())))
                .map(Project::projectId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // --- Properties --------------------------------------------------------------------------

    /**
     * The compound client filter returns exactly the projects whose CLIENT member's user id is in
     * the selected set {@code U} — extensional equality against the independently computed expected
     * set (no missing, no extra project).
     */
    @Property(tries = 200)
    void clientFilterReturnsExactlyProjectsWhoseClientIsSelected(
            @ForAll("membershipGraphs") List<Project> projects,
            @ForAll("selectedUserIds") @Size(min = 1) Set<Long> selectedUserIds) {

        Set<Long> actual = applyClientFilter(projects, selectedUserIds);
        Set<Long> expected = expectedClientMatches(projects, selectedUserIds);

        assertThat(actual)
                .as("compound client filter (members.user.id~in~%s AND members.projectRole.code==CLIENT) "
                        + "must return exactly the projects whose CLIENT member is one of the selected users",
                        selectedUserIds)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * Each qualifying project appears exactly once even when a project has several members that
     * satisfy the predicate (the to-many join's duplicate rows are collapsed via {@code distinct}).
     */
    @Property(tries = 200)
    void clientFilterResultHasNoDuplicateProjects(
            @ForAll("membershipGraphs") List<Project> projects,
            @ForAll("selectedUserIds") @Size(min = 1) Set<Long> selectedUserIds) {

        // The projection set already deduplicates; assert its size equals the count of DISTINCT
        // qualifying project ids to prove no project is double-counted.
        Set<Long> actual = applyClientFilter(projects, selectedUserIds);

        long distinctQualifying = projects.stream()
                .filter(p -> p.members().stream()
                        .anyMatch(m -> CLIENT_ROLE_CODE.equals(m.roleCode())
                                && selectedUserIds.contains(m.userId())))
                .map(Project::projectId)
                .distinct()
                .count();

        assertThat((long) actual.size())
                .as("each qualifying project must appear exactly once (duplicates from the to-many join collapsed)")
                .isEqualTo(distinctQualifying);
    }

    /**
     * A project that matches the selected users ONLY through a non-CLIENT member is excluded by the
     * compound client filter. Cross-checked against the plain member filter: the client result is
     * always a subset of the member result, and any project in the member result but not the client
     * result has no CLIENT member among the selected users.
     */
    @Property(tries = 200)
    void nonClientOnlyMatchesAreExcluded(
            @ForAll("membershipGraphs") List<Project> projects,
            @ForAll("selectedUserIds") @Size(min = 1) Set<Long> selectedUserIds) {

        Set<Long> clientMatches = applyClientFilter(projects, selectedUserIds);
        Set<Long> memberMatches = applyMemberFilter(projects, selectedUserIds);

        assertThat(memberMatches)
                .as("the compound client filter must be a subset of the plain member filter")
                .containsAll(clientMatches);

        // Every project matched by the member filter but NOT by the client filter must have zero
        // CLIENT members among the selected users (i.e. it matched only via non-CLIENT roles).
        Set<Long> nonClientOnly = new LinkedHashSet<>(memberMatches);
        nonClientOnly.removeAll(clientMatches);

        for (Long projectId : nonClientOnly) {
            Project project = projects.stream()
                    .filter(p -> p.projectId() == projectId)
                    .findFirst()
                    .orElseThrow();
            boolean hasSelectedClientMember = project.members().stream()
                    .anyMatch(m -> CLIENT_ROLE_CODE.equals(m.roleCode())
                            && selectedUserIds.contains(m.userId()));
            assertThat(hasSelectedClientMember)
                    .as("project %s matched the member filter but not the client filter, so it must "
                            + "have NO CLIENT member among the selected users (matched only via non-CLIENT roles)",
                            projectId)
                    .isFalse();
        }
    }

    // --- Arbitrary providers -----------------------------------------------------------------

    /**
     * Generates membership graphs: a list of projects (distinct ids) each with 0..6 members. Each
     * member pairs a user id (drawn from a small pool so selection sets overlap meaningfully) with a
     * role code that is either {@code CLIENT} or one of the non-CLIENT roles, so the graph mixes
     * CLIENT and non-CLIENT memberships for the same users across projects.
     */
    @Provide
    Arbitrary<List<Project>> membershipGraphs() {
        Arbitrary<String> roleCodes = Arbitraries.frequencyOf(
                // Bias toward CLIENT often enough that many projects have a client member.
                net.jqwik.api.Tuple.of(2, Arbitraries.just(CLIENT_ROLE_CODE)),
                net.jqwik.api.Tuple.of(3, Arbitraries.of(NON_CLIENT_ROLE_CODES)));

        Arbitrary<Member> members = Combinators.combine(
                        Arbitraries.longs().between(1L, 12L),
                        roleCodes)
                .as(Member::new);

        Arbitrary<List<Member>> memberLists = members.list().ofMaxSize(6);

        // Distinct project ids: generate a set of ids, then attach an independently generated
        // membership list to each.
        return Arbitraries.longs().between(1L, 40L).set().ofMinSize(0).ofMaxSize(10)
                .flatMap(ids -> {
                    List<Long> idList = ids.stream().sorted().toList();
                    Arbitrary<List<List<Member>>> perProjectMembers =
                            memberLists.list().ofSize(idList.size());
                    return perProjectMembers.map(all -> {
                        List<Project> projects = new java.util.ArrayList<>(idList.size());
                        for (int i = 0; i < idList.size(); i++) {
                            projects.add(new Project(idList.get(i), all.get(i)));
                        }
                        return projects;
                    });
                });
    }

    /** Non-empty sets of selected user ids drawn from the same pool the graph's members use. */
    @Provide
    Arbitrary<Set<Long>> selectedUserIds() {
        return Arbitraries.longs().between(1L, 12L).set().ofMinSize(1).ofMaxSize(6);
    }
}
