package com.foremen.service.execution.amendment;

/**
 * FOR-06-08 (Requirement 5) — the user's explicit choice on a same-subject {@code Merge_Conflict}
 * between an existing open-amendment line and a new change whose {@link AmendmentSubject} coincides
 * with it.
 *
 * <p>The System never silently combines two same-subject deltas; when a conflict is detected
 * ({@link AmendmentMergeResolver#conflicts}) it surfaces the existing line and the new input and
 * requires the caller to pick one of these resolutions (Requirement 5.1). At most one line per
 * subject survives afterwards (Requirement 5.4):
 *
 * <ul>
 *   <li>{@link #REPLACE} — the incoming delta overwrites the existing line's delta (use the new
 *       volume + new price / the new schedule delta).</li>
 *   <li>{@link #KEEP_EXISTING} — the incoming input is discarded; the existing line is unchanged.</li>
 *   <li>{@link #ADD_TO} — the two deltas combine: for {@code SCHEDULE_CHANGE} the signed half-day
 *       deltas are summed; for {@code VOLUME_CHANGE} / {@code ADDITIONAL_WORK} the {@code
 *       deltaQuantity} values are summed and the new {@code extraUnitPrice} is taken (Requirement 5.2,
 *       5.3).</li>
 * </ul>
 *
 * <p>Which resolutions the authoring UI offers for a given {@code Change_Type} is given by
 * {@link AmendmentMergeResolver#offered}.
 */
public enum MergeResolution {
    REPLACE,
    KEEP_EXISTING,
    ADD_TO
}
