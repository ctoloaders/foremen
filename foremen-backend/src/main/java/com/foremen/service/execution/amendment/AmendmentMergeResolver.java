package com.foremen.service.execution.amendment;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.foremen.dao.model.ChangeType;
import com.foremen.exception.ForemenApiException;

/**
 * FOR-06-08 (Requirement 5) — the pure, persistence-free merge core of the amendment-authoring
 * module. It owns the {@code Amendment_Subject} identity and the {@code Merge_Resolution} arithmetic
 * that collapse two same-subject changes into a single surviving line. It performs no I/O and holds no
 * state, so the merge rules are directly jqwik-testable without a Spring / persistence context (design
 * §Backend components; the service is a thin apply-layer around this).
 *
 * <p>Operates on the service-model {@link AmendmentLineDelta} value type — never on a JPA entity — so
 * nothing here couples to persistence.
 *
 * <h2>Subject identity (Requirement 5.2, 5.3, 5.5)</h2>
 * <ul>
 *   <li>{@link ChangeType#SCHEDULE_CHANGE}: subject {@code (SCHEDULE_CHANGE, workCategoryId)} — two
 *       whole-project schedule changes share the {@code null}-category subject; two changes to the
 *       same category share that category.</li>
 *   <li>{@link ChangeType#ADDITIONAL_WORK} / {@link ChangeType#VOLUME_CHANGE}: subject
 *       {@code (changeType, workItemId, roomId)} — merge only when the work item AND room coincide;
 *       any difference makes the lines distinct (no conflict).</li>
 * </ul>
 *
 * <h2>Resolution arithmetic (Requirement 5.2, 5.3, 5.4)</h2>
 * <ul>
 *   <li>{@link MergeResolution#KEEP_EXISTING}: the incoming input is discarded; the existing line is
 *       returned unchanged (idempotent).</li>
 *   <li>{@link MergeResolution#REPLACE}: the incoming delta overwrites the existing line in place.</li>
 *   <li>{@link MergeResolution#ADD_TO}: for {@code SCHEDULE_CHANGE} the signed half-day deltas are
 *       summed; for {@code VOLUME_CHANGE} / {@code ADDITIONAL_WORK} the {@code deltaQuantity} values are
 *       summed and the new {@code extraUnitPrice} is taken.</li>
 * </ul>
 *
 * <p>Any resolution that writes a new cost-line {@code deltaQuantity} ({@code REPLACE} / {@code
 * ADD_TO}) rejects a result that drives the line's resulting volume ({@code baselineQuantity +
 * deltaQuantity}) negative with a 400 {@code error.amendment.volumeNegative} (Requirement 3.6 / 5.3).
 */
@Component
public class AmendmentMergeResolver {

    /** The localized message code for a resolution whose resulting volume would go negative. */
    public static final String VOLUME_NEGATIVE = "error.amendment.volumeNegative";

    /**
     * The {@code Amendment_Subject} of a line (Requirement 5.2, 5.3). For {@code SCHEDULE_CHANGE} the
     * subject keys on {@code workCategoryId} (a {@code null} category = the single whole-project
     * subject); for cost lines it keys on {@code (workItemId, roomId)}.
     *
     * @param line the line to identify (must carry a non-null {@code changeType})
     * @return the line's subject
     */
    public AmendmentSubject subjectOf(AmendmentLineDelta line) {
        Objects.requireNonNull(line, "line");
        ChangeType type = line.getChangeType();
        if (type == ChangeType.SCHEDULE_CHANGE) {
            return new AmendmentSubject(type, line.getWorkCategoryId(), null, null);
        }
        return new AmendmentSubject(type, null, line.getWorkItemId(), line.getRoomId());
    }

    /**
     * Whether two lines are the same {@code Amendment_Subject} and therefore a {@code Merge_Conflict}
     * (Requirement 5.1). Lines of different {@link ChangeType} are never in conflict (their subjects
     * differ by the {@code changeType} component).
     *
     * @param existing the line already on the open amendment
     * @param incoming the new input
     * @return {@code true} iff the subjects are equal
     */
    public boolean conflicts(AmendmentLineDelta existing, AmendmentLineDelta incoming) {
        return subjectOf(existing).equals(subjectOf(incoming));
    }

    /**
     * Apply a {@link MergeResolution} to a same-subject conflict, returning the single surviving line
     * (Requirement 5.4). {@code REPLACE} / {@code ADD_TO} mutate and return {@code existing} in place;
     * {@code KEEP_EXISTING} returns {@code existing} untouched.
     *
     * @param existing   the line already on the open amendment (mutated in place for REPLACE/ADD_TO)
     * @param incoming   the new input
     * @param resolution the user's explicit choice
     * @return the surviving line (always {@code existing})
     * @throws IllegalArgumentException if the two lines are not the same subject
     * @throws ForemenApiException      (400 {@code error.amendment.volumeNegative}) if a cost-line
     *                                  resolution drives the resulting volume negative
     */
    public AmendmentLineDelta resolve(
            AmendmentLineDelta existing, AmendmentLineDelta incoming, MergeResolution resolution) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(resolution, "resolution");
        if (!conflicts(existing, incoming)) {
            throw new IllegalArgumentException(
                    "resolve requires two lines of the same Amendment_Subject");
        }

        switch (resolution) {
            case KEEP_EXISTING:
                // Discard the incoming input; the existing line is the surviving one unchanged.
                return existing;

            case REPLACE:
                if (existing.getChangeType() == ChangeType.SCHEDULE_CHANGE) {
                    existing.setScheduleDeltaHalfDays(incoming.getScheduleDeltaHalfDays());
                } else {
                    BigDecimal newDelta = incoming.getDeltaQuantity();
                    rejectIfResultingVolumeNegative(existing, newDelta);
                    existing.setDeltaQuantity(newDelta);
                    existing.setExtraUnitPrice(incoming.getExtraUnitPrice());
                }
                return existing;

            case ADD_TO:
                if (existing.getChangeType() == ChangeType.SCHEDULE_CHANGE) {
                    existing.setScheduleDeltaHalfDays(
                            nz(existing.getScheduleDeltaHalfDays())
                                    + nz(incoming.getScheduleDeltaHalfDays()));
                } else {
                    BigDecimal summed =
                            nz(existing.getDeltaQuantity()).add(nz(incoming.getDeltaQuantity()));
                    rejectIfResultingVolumeNegative(existing, summed);
                    existing.setDeltaQuantity(summed);
                    // Take the new price (Requirement 5.3).
                    existing.setExtraUnitPrice(incoming.getExtraUnitPrice());
                }
                return existing;

            default:
                throw new IllegalArgumentException("unknown MergeResolution: " + resolution);
        }
    }

    /**
     * The {@link MergeResolution}s the authoring UI should present for a given {@link ChangeType}
     * (Requirement 5.2, 5.3). Every change type offers all three — {@code REPLACE}, {@code
     * KEEP_EXISTING}, {@code ADD_TO}.
     *
     * @param changeType the conflicting lines' change type
     * @return the offered resolutions (never empty)
     */
    public Set<MergeResolution> offered(ChangeType changeType) {
        Objects.requireNonNull(changeType, "changeType");
        return EnumSet.of(
                MergeResolution.REPLACE, MergeResolution.KEEP_EXISTING, MergeResolution.ADD_TO);
    }

    /**
     * Reject a cost-line resolution whose resulting volume ({@code baselineQuantity + newDelta}) is
     * negative (Requirement 3.6 / 5.3). {@code ADDITIONAL_WORK} has no baseline ⇒ resulting volume is
     * the delta itself.
     */
    private void rejectIfResultingVolumeNegative(AmendmentLineDelta existing, BigDecimal newDelta) {
        BigDecimal resulting = nz(existing.getBaselineQuantity()).add(nz(newDelta));
        if (resulting.signum() < 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, VOLUME_NEGATIVE);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static int nz(Integer v) {
        return v != null ? v : 0;
    }
}
