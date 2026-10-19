package com.foremen.service.procurement;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the pure {@link ReprocessLinePartitioner}
 * (FOR-06-04-purchases-list, design §5.2, Requirements 5.1, 5.2, 5.3).
 *
 * <p>The partitioner is a pure, deterministic, total function from a document's prior line set to a
 * {@link ReprocessLinePartitioner.Partition} that splits the lines into those to <strong>preserve</strong>
 * (manually reconciled — {@link MatchStatus#MANUAL_MATCHED} / {@link MatchStatus#MANUAL_UNMATCHED}) and
 * those to <strong>replace</strong> (still automatic — {@link MatchStatus#AUTO} / {@link MatchStatus#UNMATCHED}).
 *
 * <p><b>Property 4 — Re-process partition preserves manual and replaces auto.</b> For any prior line
 * set the partition is a <em>disjoint cover</em>: {@code preserve ∪ replace} equals the input (order
 * preserved within each part), {@code preserve ∩ replace} is empty, and {@code preserve} is empty iff
 * no input line is manual (so when no line is manual the re-process is FOR-06-03-equivalent — replace
 * all). Validates Requirements 5.1, 5.2, 5.3.
 *
 * <p>The generator covers all four match states, lines with and without an unplanned category, and
 * the two extremes (an all-{@code AUTO}/{@code UNMATCHED} set — empty {@code preserve} — and an
 * all-{@code MANUAL_*} set — empty {@code replace}), plus the empty list and {@code null} entries.
 *
 * <p>No Spring context, no database — the partitioner is pure and exercised over in-memory entities.
 *
 * <p>Feature: FOR-06-04-purchases-list, Property 4
 */
@Tag("Feature: FOR-06-04-purchases-list, Property 4")
class ReprocessLinePartitionerPropertyTest {

    private final ReprocessLinePartitioner partitioner = new ReprocessLinePartitioner();

    // =============================================================================================
    // Property 4: Re-process partition preserves manual and replaces auto.
    // preserve ∪ replace == input (order preserved within each part); preserve ∩ replace == ∅;
    // preserve.isEmpty() ⟺ no input line is MANUAL_*.
    // Validates: Requirements 5.1, 5.2, 5.3
    // =============================================================================================

    @Property(tries = 300)
    @Tag("Feature: FOR-06-04-purchases-list, Property 4: Re-process partition preserves manual and replaces auto")
    void partitionIsADisjointCoverAndPreserveEmptyIffNoManual(@ForAll("priorLineSets") List<ProcurementLineEntity> priorLines) {
        ReprocessLinePartitioner.Partition partition = partitioner.partition(priorLines);

        List<ProcurementLineEntity> preserve = partition.preserve();
        List<ProcurementLineEntity> replace = partition.replace();

        // The partitioner skips null entries; the input, as seen by the partition, is the non-null tail.
        List<ProcurementLineEntity> nonNullInput = new ArrayList<>();
        for (ProcurementLineEntity line : priorLines) {
            if (line != null) {
                nonNullInput.add(line);
            }
        }

        // Disjoint cover 1/2 — every preserved line is manual, every replaced line is automatic.
        for (ProcurementLineEntity line : preserve) {
            assertThat(isManual(line.getMatchStatus()))
                    .as("every preserved line must be MANUAL_* (status=%s)", line.getMatchStatus())
                    .isTrue();
        }
        for (ProcurementLineEntity line : replace) {
            assertThat(isManual(line.getMatchStatus()))
                    .as("every replaced line must be AUTO/UNMATCHED (status=%s)", line.getMatchStatus())
                    .isFalse();
        }

        // Disjoint cover 2/2 — preserve ∪ replace == input (count), by identity.
        assertThat(preserve.size() + replace.size())
                .as("preserve ∪ replace must cover exactly the non-null input")
                .isEqualTo(nonNullInput.size());

        Set<ProcurementLineEntity> preserveIds = identitySet(preserve);
        Set<ProcurementLineEntity> replaceIds = identitySet(replace);

        // preserve ∩ replace == ∅.
        for (ProcurementLineEntity line : preserve) {
            assertThat(replaceIds.contains(line))
                    .as("preserve ∩ replace must be empty")
                    .isFalse();
        }

        // Every input line lands in exactly one part.
        for (ProcurementLineEntity line : nonNullInput) {
            boolean inPreserve = preserveIds.contains(line);
            boolean inReplace = replaceIds.contains(line);
            assertThat(inPreserve ^ inReplace)
                    .as("each input line must land in exactly one part")
                    .isTrue();
        }

        // Order preserved within each part — the preserved/replaced lines appear in input order.
        assertThat(preserve).containsExactlyElementsOf(filterByIdentity(nonNullInput, preserveIds));
        assertThat(replace).containsExactlyElementsOf(filterByIdentity(nonNullInput, replaceIds));

        // preserve.isEmpty() ⟺ no input line is MANUAL_* (R5.2: no manual ⇒ replace-all).
        boolean anyManual = nonNullInput.stream().anyMatch(l -> isManual(l.getMatchStatus()));
        assertThat(preserve.isEmpty())
                .as("preserve is empty iff no input line is MANUAL_*")
                .isEqualTo(!anyManual);

        // When no line is manual, every non-null input line is in replace (FOR-06-03-equivalent).
        if (!anyManual) {
            assertThat(replace).containsExactlyElementsOf(nonNullInput);
        }
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-06-04-purchases-list, Property 4: Re-process partition preserves manual and replaces auto")
    void allAutoYieldsEmptyPreserveAndAllManualYieldsEmptyReplace(
            @ForAll("automaticStatuses") MatchStatus automatic,
            @ForAll("manualStatuses") MatchStatus manual) {
        // All-automatic extreme: preserve empty, replace == input.
        List<ProcurementLineEntity> allAuto = List.of(
                line(automatic, 1), line(automatic, 2), line(automatic, 3));
        ReprocessLinePartitioner.Partition autoPartition = partitioner.partition(allAuto);
        assertThat(autoPartition.preserve()).isEmpty();
        assertThat(autoPartition.replace()).containsExactlyElementsOf(allAuto);

        // All-manual extreme: replace empty, preserve == input.
        List<ProcurementLineEntity> allManual = List.of(
                line(manual, 1), line(manual, 2), line(manual, 3));
        ReprocessLinePartitioner.Partition manualPartition = partitioner.partition(allManual);
        assertThat(manualPartition.replace()).isEmpty();
        assertThat(manualPartition.preserve()).containsExactlyElementsOf(allManual);
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private static boolean isManual(MatchStatus status) {
        return status == MatchStatus.MANUAL_MATCHED || status == MatchStatus.MANUAL_UNMATCHED;
    }

    private static Set<ProcurementLineEntity> identitySet(List<ProcurementLineEntity> lines) {
        Set<ProcurementLineEntity> set =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(lines);
        return set;
    }

    private static List<ProcurementLineEntity> filterByIdentity(
            List<ProcurementLineEntity> source, Set<ProcurementLineEntity> keep) {
        List<ProcurementLineEntity> out = new ArrayList<>();
        for (ProcurementLineEntity line : source) {
            if (keep.contains(line)) {
                out.add(line);
            }
        }
        return out;
    }

    /** A prior line in the given match state, with a tier/category shape consistent with the state. */
    private static ProcurementLineEntity line(MatchStatus status, int lineNo) {
        return line(status, lineNo, false);
    }

    private static ProcurementLineEntity line(MatchStatus status, int lineNo, boolean withCategory) {
        ProcurementLineEntity entity = new ProcurementLineEntity();
        entity.setLineNo(lineNo);
        entity.setRawName("line-" + lineNo);
        entity.setMatchStatus(status);
        boolean matched = status == MatchStatus.AUTO || status == MatchStatus.MANUAL_MATCHED;
        entity.setMatchedMaterialId(matched ? (long) lineNo : null);
        entity.setMatchTier(matched ? MatchTier.EXACT : MatchTier.NONE);
        // An unplanned category is only valid on an unmatched line; it is irrelevant to the
        // partition (which keys purely on matchStatus) but exercises the "with category" shape.
        if (withCategory && !matched) {
            UnplannedExpenseCategoryEntity category = new UnplannedExpenseCategoryEntity();
            category.setCode("CAT-" + lineNo);
            category.setNameRU("ru");
            category.setNamePL("pl");
            category.setActive(true);
            entity.setUnplannedCategory(category);
        }
        return entity;
    }

    // =============================================================================================
    // Generators
    // =============================================================================================

    @Provide
    Arbitrary<MatchStatus> automaticStatuses() {
        return Arbitraries.of(MatchStatus.AUTO, MatchStatus.UNMATCHED);
    }

    @Provide
    Arbitrary<MatchStatus> manualStatuses() {
        return Arbitraries.of(MatchStatus.MANUAL_MATCHED, MatchStatus.MANUAL_UNMATCHED);
    }

    /**
     * A prior line set over all four match states, lines with and without an unplanned category,
     * including the empty list and occasional {@code null} entries (which the partitioner ignores).
     * Line numbers are assigned post-hoc so the generated entities are distinct instances.
     */
    @Provide
    Arbitrary<List<ProcurementLineEntity>> priorLineSets() {
        Arbitrary<MatchStatus> status = Arbitraries.of(MatchStatus.values());
        Arbitrary<Boolean> withCategory = Arbitraries.of(true, false);
        Arbitrary<Boolean> isNull = Arbitraries.frequency(
                net.jqwik.api.Tuple.of(9, false),
                net.jqwik.api.Tuple.of(1, true));

        Arbitrary<LineSpec> spec = Combinators.combine(status, withCategory, isNull)
                .as(LineSpec::new);

        return spec.list().ofMinSize(0).ofMaxSize(12).map(specs -> {
            List<ProcurementLineEntity> lines = new ArrayList<>();
            int lineNo = 1;
            for (LineSpec s : specs) {
                if (s.isNull()) {
                    lines.add(null);
                } else {
                    lines.add(line(s.status(), lineNo++, s.withCategory()));
                }
            }
            return lines;
        });
    }

    record LineSpec(MatchStatus status, boolean withCategory, boolean isNull) {
    }
}
