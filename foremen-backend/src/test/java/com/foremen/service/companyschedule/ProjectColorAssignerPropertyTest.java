package com.foremen.service.companyschedule;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the deterministic {@code Project_Color} assignment of
 * {@link ProjectColorAssigner#assignColors(java.util.List)} (FOR-07-01-cross-project-schedule-core,
 * design §"Correctness Properties", Property 4).
 *
 * <p>{@code assignColors} is a pure, side-effect-free function over a list of project ids — no
 * database, no Spring context, no clock — so this property is cheap to run over 100+ generated
 * inputs that exercise arbitrary order, duplicates, and {@code null} entries (mirrors
 * {@code ScheduleCalculatorPropertyTest}).
 *
 * <p><b>Property 4 (Deterministic color by project id):</b> the same <em>set</em> of project ids
 * always yields the same palette index per id regardless of the input's order or duplicate
 * multiplicity; the index assigned to an id is its rank in the ascending-id ranking taken
 * {@code mod PALETTE_SIZE}; and every index is in {@code [0, PALETTE_SIZE)}.
 *
 * <p>Feature: FOR-07-01-cross-project-schedule-core, Property 4: Deterministic color by project id
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3</b>
 */
@Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 4: Deterministic color by project id")
class ProjectColorAssignerPropertyTest {

    // ------------------------------------------------------------------------------------------
    // Property 4a: the assignment is independent of input order and duplicate multiplicity
    // (same project-id SET -> identical colorIndex per id). (Requirements 3.1, 3.2)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 4: Deterministic color by project id")
    void sameSetYieldsSameColorRegardlessOfOrderOrDuplicates(
            @ForAll("projectIdLists") List<Long> projectIds,
            @ForAll long shuffleSeed,
            @ForAll("duplicationFactors") int duplicationFactor) {

        // A first projection over the ids exactly as generated.
        Map<Long, Integer> first = ProjectColorAssigner.assignColors(projectIds);

        // A second projection over the SAME set, but shuffled and with every id duplicated
        // duplicationFactor times (and interleaved with the originals). Same set, different list.
        List<Long> reordered = new ArrayList<>(projectIds);
        for (int i = 0; i < duplicationFactor; i++) {
            reordered.addAll(projectIds);
        }
        Collections.shuffle(reordered, new Random(shuffleSeed));
        Map<Long, Integer> second = ProjectColorAssigner.assignColors(reordered);

        // Determinism by id SET: every non-null id gets the identical colorIndex in both projections.
        assertThat(second).isEqualTo(first);
    }

    // ------------------------------------------------------------------------------------------
    // Property 4b: ascending-id ranking is stable and indices are in [0, PALETTE_SIZE).
    // The index of an id equals its 0-based rank among the distinct ids (ascending) mod PALETTE_SIZE.
    // (Requirements 3.2, 3.3)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 4: Deterministic color by project id")
    void indexIsAscendingRankModPaletteAndInRange(
            @ForAll("projectIdLists") List<Long> projectIds) {

        Map<Long, Integer> colors = ProjectColorAssigner.assignColors(projectIds);

        // Independently compute the expected ascending ranking of the distinct, non-null ids.
        TreeSet<Long> distinctAscending = new TreeSet<>();
        for (Long id : projectIds) {
            if (id != null) {
                distinctAscending.add(id);
            }
        }

        // Exactly one entry per distinct non-null id.
        assertThat(colors).hasSize(distinctAscending.size());
        assertThat(colors.keySet()).containsExactlyInAnyOrderElementsOf(distinctAscending);

        int rank = 0;
        for (Long id : distinctAscending) {
            Integer index = colors.get(id);
            // Index equals ascending rank mod PALETTE_SIZE (stable ranking).
            assertThat(index).isEqualTo(rank % ProjectColorAssigner.PALETTE_SIZE);
            // Index is always in [0, PALETTE_SIZE).
            assertThat(index).isGreaterThanOrEqualTo(0);
            assertThat(index).isLessThan(ProjectColorAssigner.PALETTE_SIZE);
            rank++;
        }

        // The map iteration order is the stable ascending-id order (stable legend order).
        assertThat(colors.keySet()).containsExactlyElementsOf(distinctAscending);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * Lists of 0..40 project ids. Ids are drawn from a bounded range so collisions (duplicates) and
     * a distinct count that exceeds {@link ProjectColorAssigner#PALETTE_SIZE} both occur frequently,
     * exercising the {@code rank % PALETTE_SIZE} wrap. {@code null} entries are sprinkled in to
     * confirm they are ignored.
     */
    @Provide
    Arbitrary<List<Long>> projectIdLists() {
        Arbitrary<Long> ids = Arbitraries.oneOf(
                Arbitraries.longs().between(1L, 60L),
                Arbitraries.just((Long) null));
        return ids.list().ofMinSize(0).ofMaxSize(40);
    }

    @Provide
    Arbitrary<Integer> duplicationFactors() {
        return Arbitraries.integers().between(0, 3);
    }
}
