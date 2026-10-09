package com.foremen.service.team;

import java.util.Comparator;
import java.util.List;

/**
 * Pure helper deriving a {@code Project_Member}'s {@link TeamBlock} from its {@code Company_Role}
 * and ordering members into the deterministic Team_Block order (FOR-05-09 Requirements 4.6, 6.2).
 *
 * <p><strong>Block derivation</strong> ({@link #blockOf(String)}) maps a role code to its block
 * purely from the role code (D3/D6): {@code CLIENT} &rarr; {@link TeamBlock#CLIENTS},
 * {@code WORKER} &rarr; {@link TeamBlock#WORKERS}, every other (admin-staff) role &rarr;
 * {@link TeamBlock#ADMIN_STAFF}. A {@code null} or blank role code is treated as admin-staff so an
 * unexpected role never falls out of the three blocks. Matching is case-insensitive on the trimmed
 * code.
 *
 * <p><strong>Ordering</strong> ({@link #comparator()}) sorts members by, in order:
 * <ol>
 *   <li>{@link TeamBlock} (ADMIN_STAFF, then WORKERS, then CLIENTS);</li>
 *   <li>within {@link TeamBlock#ADMIN_STAFF} only, by role code rank — MANAGER, FOREMAN, ESTIMATOR,
 *       FINANCIER, then any other admin-staff role (ties among "any other" broken by the role code
 *       itself, case-insensitively, so the order stays total and deterministic);</li>
 *   <li>{@code userName} ascending, case-insensitive (a {@code null} name sorts last);</li>
 *   <li>{@code id} ascending (a {@code null} id sorts last).</li>
 * </ol>
 * The {@code Assignment_Status} is deliberately <strong>not</strong> part of the key: an INACTIVE
 * member sorts exactly where its ACTIVE counterpart would (Requirement 4.6).
 *
 * <p>This class is pure: it holds no state, performs no I/O, and its results depend only on the
 * supplied arguments. It is not instantiable.
 */
public final class TeamMemberOrdering {

    private static final String CLIENT_ROLE = "CLIENT";
    private static final String WORKER_ROLE = "WORKER";

    /**
     * Admin-staff role codes in their canonical intra-block rank. A role not listed here (any other
     * admin-staff role) ranks after every listed one.
     */
    private static final List<String> ADMIN_STAFF_RANK =
            List.of("MANAGER", "FOREMAN", "ESTIMATOR", "FINANCIER");

    /** Rank assigned to an admin-staff role not present in {@link #ADMIN_STAFF_RANK}. */
    private static final int RANK_OTHER = ADMIN_STAFF_RANK.size();

    private TeamMemberOrdering() {
    }

    /**
     * Something orderable by the Team_Block comparator. The {@code Team_Member_View} and any other
     * row carrying these three fields can be sorted by {@link #comparator()} without the comparator
     * depending on the concrete model.
     */
    public interface Orderable {

        /** The member's {@code Company_Role} code (== {@code Project_Role} code, D2); may be {@code null}. */
        String companyRoleCode();

        /** The member user's display name; may be {@code null}. */
        String userName();

        /** The membership id; may be {@code null}. */
        Long id();
    }

    /**
     * Derives the {@link TeamBlock} of a member from its {@code Company_Role} code.
     *
     * @param roleCode the member's role code; {@code null}/blank is treated as admin-staff
     * @return {@link TeamBlock#CLIENTS} for {@code CLIENT}, {@link TeamBlock#WORKERS} for
     *         {@code WORKER}, otherwise {@link TeamBlock#ADMIN_STAFF}
     */
    public static TeamBlock blockOf(String roleCode) {
        String code = normalize(roleCode);
        if (CLIENT_ROLE.equals(code)) {
            return TeamBlock.CLIENTS;
        }
        if (WORKER_ROLE.equals(code)) {
            return TeamBlock.WORKERS;
        }
        return TeamBlock.ADMIN_STAFF;
    }

    /**
     * Returns the total, deterministic Team_Block comparator described in the class javadoc. The
     * comparator is stateless and safe to reuse.
     */
    public static Comparator<Orderable> comparator() {
        return Comparator
                .comparingInt((Orderable m) -> blockOf(m.companyRoleCode()).ordinal())
                .thenComparingInt(TeamMemberOrdering::adminStaffRank)
                .thenComparing(TeamMemberOrdering::rankTieBreakRoleCode, nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(TeamMemberOrdering::userNameKey, nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(TeamMemberOrdering::idKey, nullsLast(Long::compareTo));
    }

    /**
     * The intra-block rank of a member within {@link TeamBlock#ADMIN_STAFF}: the index of its role
     * code in {@link #ADMIN_STAFF_RANK}, or {@link #RANK_OTHER} for any other admin-staff role.
     * Members outside ADMIN_STAFF all share {@link #RANK_OTHER}, so this key never reorders the
     * WORKERS or CLIENTS blocks (those blocks have a single role code each).
     */
    private static int adminStaffRank(Orderable member) {
        if (blockOf(member.companyRoleCode()) != TeamBlock.ADMIN_STAFF) {
            return RANK_OTHER;
        }
        int idx = ADMIN_STAFF_RANK.indexOf(normalize(member.companyRoleCode()));
        return idx >= 0 ? idx : RANK_OTHER;
    }

    /**
     * Tie-breaker among admin-staff roles that share {@link #RANK_OTHER} (the "any other" roles):
     * their raw role code, so two distinct unranked admin-staff roles keep a stable total order.
     * Ranked admin-staff roles and the single-role WORKERS/CLIENTS blocks return {@code null} so
     * this key does not affect them.
     */
    private static String rankTieBreakRoleCode(Orderable member) {
        if (adminStaffRank(member) != RANK_OTHER) {
            return null;
        }
        if (blockOf(member.companyRoleCode()) != TeamBlock.ADMIN_STAFF) {
            return null;
        }
        return normalize(member.companyRoleCode());
    }

    private static String userNameKey(Orderable member) {
        return member.userName();
    }

    private static Long idKey(Orderable member) {
        return member.id();
    }

    private static String normalize(String roleCode) {
        return roleCode == null ? null : roleCode.trim().toUpperCase();
    }

    private static <T> Comparator<T> nullsLast(Comparator<T> comparator) {
        return Comparator.nullsLast(comparator);
    }
}
