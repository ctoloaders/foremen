package com.foremen.service.procurement;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.ProcurementLineEntity;

/**
 * FOR-06-04 (design §5.2, Requirements 5.1, 5.2, 5.3) — the pure, deterministic manual-preservation
 * rule injected into the FOR-06-03 re-process path (completes FOR-06-03 R8.4).
 *
 * <p>Given a document's prior lines, {@link #partition(List)} splits them into the lines to
 * <strong>preserve</strong> (a user manually reconciled them — {@link MatchStatus#MANUAL_MATCHED} /
 * {@link MatchStatus#MANUAL_UNMATCHED}) and the lines to <strong>replace</strong> (produced by the
 * automatic match — {@link MatchStatus#AUTO} / {@link MatchStatus#UNMATCHED}). The caller drops only
 * {@code replace}, appends the freshly extracted + auto-matched lines, and keeps {@code preserve}
 * with their {@code matchedMaterialId} / {@code unplannedCategory} intact.
 *
 * <p>The partition is a <strong>disjoint cover</strong> of the input: {@code preserve ∪ replace}
 * equals the input (order preserved within each part) and {@code preserve ∩ replace} is empty. When
 * no prior line is manual, {@code preserve} is empty and the re-process is FOR-06-03-equivalent
 * (replace all, R5.2). The component is pure — no I/O, no persistence — so it is unit- and
 * property-testable without the OCR provider (R5.3).
 */
@Component
public final class ReprocessLinePartitioner {

    /**
     * Split the document's prior lines into those to preserve (manual) and those to drop (auto).
     *
     * @param priorLines the document's existing lines (may be empty); {@code null} entries are ignored
     * @return the disjoint {@link Partition}; never {@code null}
     */
    public Partition partition(List<ProcurementLineEntity> priorLines) {
        List<ProcurementLineEntity> preserve = new ArrayList<>();
        List<ProcurementLineEntity> replace = new ArrayList<>();
        if (priorLines != null) {
            for (ProcurementLineEntity line : priorLines) {
                if (line == null) {
                    continue;
                }
                if (isManual(line.getMatchStatus())) {
                    preserve.add(line);
                } else {
                    replace.add(line);
                }
            }
        }
        return new Partition(preserve, replace);
    }

    /** A line is preserved iff it was manually reconciled (R5.1). */
    private static boolean isManual(MatchStatus status) {
        return status == MatchStatus.MANUAL_MATCHED || status == MatchStatus.MANUAL_UNMATCHED;
    }

    /**
     * The result of {@link #partition(List)}: the manual lines to keep and the automatic lines to
     * drop. The two lists are disjoint and together cover the input.
     *
     * @param preserve lines with {@code matchStatus ∈ {MANUAL_MATCHED, MANUAL_UNMATCHED}}
     * @param replace  lines with {@code matchStatus ∈ {AUTO, UNMATCHED}}
     */
    public record Partition(List<ProcurementLineEntity> preserve,
                            List<ProcurementLineEntity> replace) {
    }
}
