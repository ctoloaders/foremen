package com.foremen.service.execution.amendment;

import com.foremen.dao.model.ChangeType;

/**
 * FOR-06-08 (Requirement 5) — the {@code Amendment_Subject}: the identity a same-subject merge
 * compares against. Two open-amendment lines are the "same subject" (and therefore a
 * {@code Merge_Conflict}) iff their subjects are equal.
 *
 * <p>The subject is type-dependent (design §Data model, {@link AmendmentMergeResolver#subjectOf}):
 *
 * <ul>
 *   <li>{@link ChangeType#SCHEDULE_CHANGE} — {@code (SCHEDULE_CHANGE, workCategoryId)}: two
 *       whole-project schedule changes share the subject with a {@code null} category; two changes to
 *       the same category share that category (Requirement 5.2). {@code workItemId} / {@code roomId}
 *       are {@code null}.</li>
 *   <li>{@link ChangeType#ADDITIONAL_WORK} / {@link ChangeType#VOLUME_CHANGE} — {@code (changeType,
 *       workItemId, roomId)}: the same work item in the same room (Requirement 5.3). {@code
 *       workCategoryId} is {@code null}. If the work item OR the room differs the subjects are
 *       distinct and the lines never merge (Requirement 5.5).</li>
 * </ul>
 *
 * <p>A {@code null} {@code workCategoryId} (the whole-project schedule subject) is a <b>significant</b>
 * value here — unlike a plain SQL unique index, record equality treats two {@code null} categories as
 * equal, which is exactly the whole-project single-subject rule.
 *
 * @param changeType     the line's change type (never {@code null})
 * @param workCategoryId the schedule target category, or {@code null} (whole project / non-schedule)
 * @param workItemId     the cost-line work item, or {@code null} (schedule)
 * @param roomId         the cost-line room, or {@code null} (schedule)
 */
public record AmendmentSubject(
        ChangeType changeType,
        Long workCategoryId,
        Long workItemId,
        Long roomId) {
}
