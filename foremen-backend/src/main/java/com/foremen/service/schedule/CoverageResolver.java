package com.foremen.service.schedule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure resolution of category <em>coverage</em> for a project's per-worker planning Gantt
 * (FOR-05-10b Requirements 4.1, 4.2, 8.2). Given the set of ACTIVE WORKER project members — each
 * carrying their ordered Member_Work_Categories — and the set of estimate work categories, it
 * computes, for every category, the ordered list of {@code Covering_Worker}s (the members whose
 * categories include it) and the {@code Uncovered_Category} set (categories no member covers).
 *
 * <p>This is a <strong>pure</strong> helper in the same vein as {@link ScheduleCalculator} and
 * {@link ScheduleRowDerivation}: every method is static and side-effect free — no database, no
 * Spring context, no clock — and the result depends only on the arguments, so each rule is directly
 * unit- and property-testable (task 2.2). It works on plain ids (member ids, category ids) rather
 * than JPA entities so the service owns the ACTIVE/WORKER filtering and the entity → id mapping, and
 * the resolver stays trivially testable.
 *
 * <p>Semantics (design §"Coverage and auto-layout → CoverageResolver"):
 * <pre>
 *   coveringWorkers(category) = activeWorkerMembers.filter(m -&gt; m.workCategories.contains(category))
 *                                                  .sortedBy(member.id)
 *   uncovered                 = categories.filter(c -&gt; coveringWorkers(c).isEmpty())
 * </pre>
 *
 * <p>The covering-worker lists are ordered by {@code member.id} ascending; the per-category map and
 * the uncovered set preserve the <em>input category order</em> (the {@code Schedule_Row} order the
 * caller derived from {@link ScheduleRowDerivation}). A category appears in the result exactly once
 * even if the caller passes duplicates; a member covering the same category more than once (an
 * ill-formed Member_Work_Categories list) contributes to that category only once.
 */
public final class CoverageResolver {

    private CoverageResolver() {
    }

    /**
     * One ACTIVE WORKER member's coverage input: the member's id and the (unordered, for coverage
     * purposes) set of work-category ids they can perform. The caller is responsible for supplying
     * only ACTIVE WORKER members (D-worker-only, R8.1 handles the "no worker at all" case
     * elsewhere); this record carries no status of its own.
     *
     * <p>{@code memberId} must be non-null (coverage is keyed and ordered by it). A null entry in
     * {@code workCategoryIds} is ignored. The set is defensively copied so later mutation of the
     * caller's collection cannot change a resolved result.
     *
     * @param memberId        the {@code projectMember} id (never {@code null})
     * @param workCategoryIds the ids of the categories this member covers; may be empty, never
     *                        contributes a {@code null} id
     */
    public record WorkerMember(Long memberId, Set<Long> workCategoryIds) {

        public WorkerMember {
            Objects.requireNonNull(memberId, "memberId must not be null");
            Set<Long> copy = new LinkedHashSet<>();
            if (workCategoryIds != null) {
                for (Long id : workCategoryIds) {
                    if (id != null) {
                        copy.add(id);
                    }
                }
            }
            workCategoryIds = Set.copyOf(copy);
        }
    }

    /**
     * The resolved coverage of an estimate's categories by its ACTIVE WORKER members.
     *
     * <p>{@code coveringMembers} maps every input category id to the ordered (by member id) list of
     * member ids that cover it — an empty list for an {@code Uncovered_Category}. {@code uncovered}
     * is exactly the set of category ids whose covering list is empty. Both preserve the input
     * category order. {@code covered(categoryId)} and {@code isUncovered(categoryId)} are
     * convenience views over the same data.
     *
     * @param coveringMembers category id → ordered covering member ids (empty list = uncovered);
     *                        an immutable snapshot in input category order
     * @param uncovered       the category ids with no covering member; an immutable snapshot in
     *                        input category order
     */
    public record Coverage(Map<Long, List<Long>> coveringMembers, Set<Long> uncovered) {

        public Coverage {
            // LinkedHash* unmodifiable wrappers: keep the input category order (the Schedule_Row
            // order) while staying immutable — Map.copyOf / Set.copyOf would drop the ordering.
            coveringMembers =
                    Collections.unmodifiableMap(new LinkedHashMap<>(coveringMembers));
            uncovered = Collections.unmodifiableSet(new LinkedHashSet<>(uncovered));
        }

        /**
         * The ordered (by member id) covering member ids of a category, or an empty list when the
         * category is uncovered or was not among the resolved categories.
         *
         * @param categoryId the work-category id
         * @return the covering member ids in ascending member-id order; never {@code null}
         */
        public List<Long> coveringWorkers(Long categoryId) {
            return coveringMembers.getOrDefault(categoryId, List.of());
        }

        /**
         * Whether a resolved category has at least one covering member.
         *
         * @param categoryId the work-category id
         * @return {@code true} iff the category has one or more covering members
         */
        public boolean covered(Long categoryId) {
            return !coveringWorkers(categoryId).isEmpty();
        }

        /**
         * Whether a resolved category has no covering member (an {@code Uncovered_Category}).
         *
         * @param categoryId the work-category id
         * @return {@code true} iff the category is in the uncovered set
         */
        public boolean isUncovered(Long categoryId) {
            return uncovered.contains(categoryId);
        }
    }

    /**
     * Resolves the coverage of {@code categoryIds} by {@code activeWorkerMembers}.
     *
     * <p>For each category (in input order, de-duplicated keeping the first occurrence) the covering
     * members are the members whose {@link WorkerMember#workCategoryIds()} contain that category,
     * listed in ascending {@code memberId} order (R4.1). A category covered by no member is placed in
     * the {@code uncovered} set and maps to an empty covering list (R4.2, D-uncovered); the uncovered
     * set is thus exactly the categories whose covering list is empty (R8.2).
     *
     * @param categoryIds         the estimate work-category ids in {@code Schedule_Row} order; may be
     *                            empty (yields an empty coverage) and may contain {@code null}
     *                            entries (ignored) or duplicates (first occurrence kept)
     * @param activeWorkerMembers the ACTIVE WORKER members with their category sets; may be empty, in
     *                            which case every category is uncovered (R4.6/R8.1 handled by the
     *                            caller). May contain {@code null} entries (ignored)
     * @return the resolved {@link Coverage}; never {@code null}
     */
    public static Coverage resolve(
            Collection<Long> categoryIds, Collection<WorkerMember> activeWorkerMembers) {

        // De-duplicate categories keeping first-seen input order (the Schedule_Row order).
        LinkedHashSet<Long> categories = new LinkedHashSet<>();
        if (categoryIds != null) {
            for (Long id : categoryIds) {
                if (id != null) {
                    categories.add(id);
                }
            }
        }

        List<WorkerMember> members = new ArrayList<>();
        if (activeWorkerMembers != null) {
            for (WorkerMember member : activeWorkerMembers) {
                if (member != null) {
                    members.add(member);
                }
            }
        }
        // Covering lists are ordered by member id ascending (R4.1), independent of input order.
        members.sort(Comparator.comparing(WorkerMember::memberId));

        Map<Long, List<Long>> coveringMembers = new LinkedHashMap<>();
        LinkedHashSet<Long> uncovered = new LinkedHashSet<>();
        for (Long categoryId : categories) {
            List<Long> covering = new ArrayList<>();
            for (WorkerMember member : members) {
                if (member.workCategoryIds().contains(categoryId)) {
                    covering.add(member.memberId());
                }
            }
            coveringMembers.put(categoryId, List.copyOf(covering));
            if (covering.isEmpty()) {
                uncovered.add(categoryId);
            }
        }

        return new Coverage(coveringMembers, uncovered);
    }
}
