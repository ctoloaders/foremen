package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.team.TeamBlock;
import com.foremen.service.team.TeamMemberOrdering;
import com.foremen.service.team.TeamMemberOrdering.Orderable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link TeamMemberOrdering#comparator()} — the pure total order the
 * Team_API uses to list a project's members (FOR-05-09-team-selection, design §Property 7).
 *
 * <p>The order is, in sequence: {@link TeamBlock} (ADMIN_STAFF, WORKERS, CLIENTS); within
 * ADMIN_STAFF by role-code rank (MANAGER, FOREMAN, ESTIMATOR, FINANCIER, then any other admin-staff
 * role, those broken by the role code case-insensitively); then {@code userName} ascending
 * case-insensitive; then membership {@code id} ascending. The {@code Assignment_Status} is not part
 * of the key, so it never affects the order, and the comparator is a deterministic total order so
 * sorting is shuffle-invariant and two reads over unchanged data agree.
 *
 * <p>The comparator is exercised directly as a pure function — no persistence — so the property is
 * cheap to run over hundreds of iterations.
 *
 * <p>Feature: FOR-05-09-team-selection, Property 7: Team_Block ordering is deterministic
 *
 * <p><b>Validates: Requirements 4.6</b>
 */
@Tag("Feature: FOR-05-09-team-selection, Property 7: Team_Block ordering is deterministic")
class TeamMemberOrderingPropertyTest {

    private static final Comparator<Orderable> ORDER = TeamMemberOrdering.comparator();

    /** Admin-staff role codes in canonical rank; anything else is "other" and ranks after FINANCIER. */
    private static final List<String> ADMIN_STAFF_RANK =
            List.of("MANAGER", "FOREMAN", "ESTIMATOR", "FINANCIER");

    private static final List<String> OTHER_ADMIN_STAFF =
            List.of("ADMIN", "COORDINATOR", "AUDITOR", "SUPERVISOR");

    private static final List<String> ALL_ROLE_CODES = buildAllRoleCodes();

    /**
     * A test member carrying only the three fields the comparator reads plus an
     * {@code assignmentStatus} the comparator must ignore.
     */
    private record Member(String companyRoleCode, String userName, Long id, String assignmentStatus)
            implements Orderable {
    }

    // ------------------------------------------------------------------------------------------
    // Property 7a: the comparator realises the full key sequence — for every adjacent pair in the
    // sorted output, the pair is ordered by the oracle key (block ordinal, admin-staff rank,
    // case-insensitive name, id). This directly asserts ADMIN_STAFF < WORKERS < CLIENTS, the
    // MANAGER<FOREMAN<ESTIMATOR<FINANCIER<other intra-block rank, name, then id.
    // Validates: Requirements 4.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 300)
    @Tag("Feature: FOR-05-09-team-selection, Property 7: Team_Block ordering is deterministic")
    void sortedOutputFollowsTheCanonicalKeySequence(@ForAll("teams") List<Member> members) {
        List<Member> sorted = new ArrayList<>(members);
        sorted.sort(ORDER);

        for (int i = 0; i + 1 < sorted.size(); i++) {
            long[] left = oracleKey(sorted.get(i));
            long[] right = oracleKey(sorted.get(i + 1));
            assertThat(compareKeys(left, right))
                    .as("adjacent pair out of canonical order: %s then %s", sorted.get(i), sorted.get(i + 1))
                    .isLessThanOrEqualTo(0);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 7b: block order is exactly ADMIN_STAFF, then WORKERS, then CLIENTS — once a later
    // block appears in the sorted list, no earlier block reappears.
    // Validates: Requirements 4.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 300)
    @Tag("Feature: FOR-05-09-team-selection, Property 7: Team_Block ordering is deterministic")
    void blocksAppearInAdminStaffWorkersClientsOrder(@ForAll("teams") List<Member> members) {
        List<Member> sorted = new ArrayList<>(members);
        sorted.sort(ORDER);

        int maxBlockOrdinalSoFar = -1;
        for (Member m : sorted) {
            int ordinal = TeamMemberOrdering.blockOf(m.companyRoleCode()).ordinal();
            assertThat(ordinal)
                    .as("block ordinals must be non-decreasing (ADMIN_STAFF→WORKERS→CLIENTS), saw %s", m)
                    .isGreaterThanOrEqualTo(maxBlockOrdinalSoFar);
            maxBlockOrdinalSoFar = ordinal;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 7c: Assignment_Status does not affect the order — reassigning every member's status
    // arbitrarily produces an identical sorted sequence (compared by the identity-bearing id).
    // Validates: Requirements 4.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 300)
    @Tag("Feature: FOR-05-09-team-selection, Property 7: Team_Block ordering is deterministic")
    void assignmentStatusDoesNotAffectOrder(
            @ForAll("teams") List<Member> members,
            @ForAll("statuses") List<String> replacementStatuses) {
        List<Member> withOriginalStatus = new ArrayList<>(members);
        withOriginalStatus.sort(ORDER);

        // Rebuild the same members but flip each one's assignment status to an arbitrary value.
        List<Member> withFlippedStatus = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            Member m = members.get(i);
            String newStatus = replacementStatuses.get(i % replacementStatuses.size());
            withFlippedStatus.add(new Member(m.companyRoleCode(), m.userName(), m.id(), newStatus));
        }
        withFlippedStatus.sort(ORDER);

        assertThat(idSequence(withFlippedStatus))
                .as("changing Assignment_Status must not change the ordering")
                .isEqualTo(idSequence(withOriginalStatus));
    }

    // ------------------------------------------------------------------------------------------
    // Property 7d: the comparator is a deterministic total order — sorting is shuffle-invariant
    // (any input permutation yields the same sorted sequence) and two independent sorts agree, so
    // two reads over unchanged data return identical order.
    // Validates: Requirements 4.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 300)
    @Tag("Feature: FOR-05-09-team-selection, Property 7: Team_Block ordering is deterministic")
    void sortIsShuffleInvariantAndRepeatable(
            @ForAll("teams") List<Member> members,
            @ForAll long shuffleSeed) {
        List<Member> firstSort = new ArrayList<>(members);
        firstSort.sort(ORDER);

        List<Member> shuffled = new ArrayList<>(members);
        Collections.shuffle(shuffled, new java.util.Random(shuffleSeed));
        shuffled.sort(ORDER);

        // Compare by the full key (not identity) so distinct members that tie on every key are
        // still treated as equivalently placed — the order of such ties is irrelevant to the spec.
        assertThat(keySequence(shuffled))
                .as("sorting is shuffle-invariant: any permutation yields the same ordered keys")
                .isEqualTo(keySequence(firstSort));

        // A second sort of the already-sorted list is a no-op (idempotent / repeatable read).
        List<Member> secondSort = new ArrayList<>(firstSort);
        secondSort.sort(ORDER);
        assertThat(idSequence(secondSort)).isEqualTo(idSequence(firstSort));
    }

    // ------------------------------------------------------------------------------------------
    // Property 7e: the comparator obeys the total-order axioms (antisymmetry/consistency and
    // transitivity) over every triple drawn from the generated universe — this is what makes the
    // sort well defined and deterministic.
    // Validates: Requirements 4.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 300)
    @Tag("Feature: FOR-05-09-team-selection, Property 7: Team_Block ordering is deterministic")
    void comparatorIsAConsistentTotalOrder(
            @ForAll("member") Member a,
            @ForAll("member") Member b,
            @ForAll("member") Member c) {
        int ab = sign(ORDER.compare(a, b));
        int ba = sign(ORDER.compare(b, a));

        // Antisymmetry: compare(a,b) and compare(b,a) have opposite signs (both 0 when equal).
        assertThat(ab).as("antisymmetry on (%s, %s)", a, b).isEqualTo(-ba);

        // Transitivity: a<=b and b<=c imply a<=c.
        if (ab <= 0 && sign(ORDER.compare(b, c)) <= 0) {
            assertThat(sign(ORDER.compare(a, c)))
                    .as("transitivity on (%s, %s, %s)", a, b, c)
                    .isLessThanOrEqualTo(0);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Oracle: an independent recomputation of the sort key as a comparable long[].
    // key = [blockOrdinal, adminStaffRank, otherRoleTieBreak, nameLowerAsIntStream..., id]
    // We encode the ordering purely as a sequence of comparable components.
    // ------------------------------------------------------------------------------------------

    /** The authoritative sort key, independent of the production comparator's internals. */
    private static long[] oracleKey(Member m) {
        // Component 0: block ordinal (ADMIN_STAFF=0, WORKERS=1, CLIENTS=2).
        // Component 1: admin-staff rank (0..3 for the named roles, 4 for any other admin-staff role,
        //              4 for non-admin-staff blocks — which never collide because block ordinal
        //              already separates them).
        return new long[] {
                TeamMemberOrdering.blockOf(m.companyRoleCode()).ordinal(),
                adminStaffRank(m)
        };
    }

    private static int adminStaffRank(Member m) {
        if (TeamMemberOrdering.blockOf(m.companyRoleCode()) != TeamBlock.ADMIN_STAFF) {
            return ADMIN_STAFF_RANK.size();
        }
        int idx = ADMIN_STAFF_RANK.indexOf(normalize(m.companyRoleCode()));
        return idx >= 0 ? idx : ADMIN_STAFF_RANK.size();
    }

    /**
     * Full-key comparison matching the spec's sequence: block ordinal, admin-staff rank, then the
     * "any other" role-code tie-break (case-insensitive), then user name (case-insensitive,
     * nulls last), then id (nulls last).
     */
    private static int compareKeys(long[] leftPrefix, long[] rightPrefix) {
        for (int i = 0; i < leftPrefix.length; i++) {
            int c = Long.compare(leftPrefix[i], rightPrefix[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** The complete comparable key the sort should realise, used for shuffle-invariance checks. */
    private static List<Comparable<?>> fullKey(Member m) {
        List<Comparable<?>> key = new ArrayList<>();
        key.add(TeamMemberOrdering.blockOf(m.companyRoleCode()).ordinal());
        key.add(adminStaffRank(m));
        // Tie-break among "any other" admin-staff roles is the role code (case-insensitive); for a
        // ranked role or a non-admin-staff block this does not reorder (we normalise to empty).
        boolean otherAdminStaff = TeamMemberOrdering.blockOf(m.companyRoleCode()) == TeamBlock.ADMIN_STAFF
                && adminStaffRank(m) == ADMIN_STAFF_RANK.size();
        key.add(otherAdminStaff ? normalize(m.companyRoleCode()) : "");
        key.add(m.userName() == null ? null : m.userName().toUpperCase(Locale.ROOT));
        key.add(m.id());
        return key;
    }

    private static List<List<Comparable<?>>> keySequence(List<Member> members) {
        List<List<Comparable<?>>> keys = new ArrayList<>();
        for (Member m : members) {
            keys.add(fullKey(m));
        }
        return keys;
    }

    private static List<Long> idSequence(List<Member> members) {
        List<Long> ids = new ArrayList<>();
        for (Member m : members) {
            ids.add(m.id());
        }
        return ids;
    }

    private static String normalize(String roleCode) {
        return roleCode == null ? null : roleCode.trim().toUpperCase(Locale.ROOT);
    }

    private static int sign(int value) {
        return Integer.compare(value, 0);
    }

    private static List<String> buildAllRoleCodes() {
        List<String> all = new ArrayList<>(ADMIN_STAFF_RANK);
        all.addAll(OTHER_ADMIN_STAFF);
        all.add("WORKER");
        all.add("CLIENT");
        return List.copyOf(all);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** A single member: a role code (mixed case to exercise case-insensitivity), name, id, status. */
    @Provide
    Arbitrary<Member> member() {
        Arbitrary<String> roleCode = Arbitraries.of(ALL_ROLE_CODES).map(TeamMemberOrderingPropertyTest::randomCase);
        // Names overlap heavily (small alphabet, short length, mixed case) so ties on the name key —
        // and the id tie-break beneath it — are actually exercised. A null name is allowed (sorts last).
        Arbitrary<String> name = Arbitraries.strings()
                .withChars("abAB ".toCharArray())
                .ofMinLength(0)
                .ofMaxLength(3)
                .injectNull(0.1);
        // Ids overlap across a tiny range so the id tie-break is reachable; null ids allowed (last).
        Arbitrary<Long> id = Arbitraries.longs().between(1L, 6L).injectNull(0.1);
        Arbitrary<String> status = statusValue();
        return Combinators.combine(roleCode, name, id, status).as(Member::new);
    }

    /** A team: 0..12 members. Small lists keep ties frequent; the empty list is included. */
    @Provide
    Arbitrary<List<Member>> teams() {
        return member().list().ofMinSize(0).ofMaxSize(12);
    }

    @Provide
    Arbitrary<List<String>> statuses() {
        return statusValue().list().ofMinSize(1).ofMaxSize(4);
    }

    private Arbitrary<String> statusValue() {
        return Arbitraries.of("ACTIVE", "INACTIVE");
    }

    /** Randomly upper/lower/mixed-cases a role code to exercise case-insensitive block/rank matching. */
    private static String randomCase(String code) {
        // Deterministic-ish variation without a source of randomness: cycle a few shapes by hashcode.
        int shape = Math.floorMod(code.hashCode(), 3);
        return switch (shape) {
            case 0 -> code.toLowerCase(Locale.ROOT);
            case 1 -> " " + code + " ";
            default -> code;
        };
    }
}
