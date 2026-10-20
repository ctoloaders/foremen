package com.foremen.controller.model;

import java.math.BigDecimal;

import com.foremen.dao.model.ChangeType;
import com.foremen.service.execution.amendment.MergeResolution;

import jakarta.validation.constraints.NotNull;

/**
 * FOR-06-08 (Requirements 3.1–3.6, 5.1, 5.6; design §Backend components, §Frontend) — the add-change
 * payload posted to {@code POST /api/amendments/{id}/lines}: a single polymorphic change line
 * discriminated by {@link #changeType} (see {@link ChangeType}), plus an optional
 * {@link #mergeResolution} used only when the change collides with an existing same-subject line.
 *
 * <p>The field set is type-dependent and validated per {@code Change_Type} server-side (R3.6); the
 * caller supplies only the fields relevant to the chosen type:
 *
 * <ul>
 *   <li>{@link ChangeType#SCHEDULE_CHANGE} — {@link #scheduleDeltaHalfDays} (signed half-days;
 *       positive = extend, negative = shorten) and optionally {@link #workCategoryId} (null =
 *       whole-project schedule shift), R3.2.</li>
 *   <li>{@link ChangeType#ADDITIONAL_WORK} — full data: {@link #workItemId}, {@link #roomId},
 *       {@link #deltaQuantity} (positive volume), {@link #extraUnitPrice} (the new unit price), and
 *       optionally {@link #description}, R3.3.</li>
 *   <li>{@link ChangeType#VOLUME_CHANGE} — {@link #workItemId}, {@link #roomId},
 *       {@link #deltaQuantity} (signed; positive = add, negative = remove), and
 *       {@link #extraUnitPrice} (the unit price on the delta volume), R3.4.</li>
 * </ul>
 *
 * <p><b>Merge (R5).</b> When the new change's {@code Amendment_Subject} already exists on the open
 * amendment and no {@link #mergeResolution} is supplied, the server rejects the write with
 * {@code error.amendment.subjectConflict} (409) carrying the existing line so the UI opens the
 * chooser; when a {@link #mergeResolution} is supplied the surviving line is merged in place
 * ({@code REPLACE} / {@code KEEP_EXISTING} / {@code ADD_TO}) so at most one line per subject remains
 * (R5.4). A distinct subject (different work item or room) simply appends a new line with no prompt
 * (R5.5). Each line stores a DELTA against the signed baseline, never an absolute value (R7.1).
 *
 * @param changeType            the change type (the discriminator; required)
 * @param workItemId            ADDITIONAL_WORK / VOLUME_CHANGE target work item
 * @param roomId                ADDITIONAL_WORK / VOLUME_CHANGE target room
 * @param workCategoryId        SCHEDULE_CHANGE target category (null = whole project)
 * @param deltaQuantity         signed volume delta (volume/additional-work)
 * @param extraUnitPrice        the new unit price (volume/additional-work)
 * @param scheduleDeltaHalfDays signed half-day schedule delta (SCHEDULE_CHANGE)
 * @param description           optional free text, untranslated
 * @param mergeResolution       the explicit {@code Merge_Resolution} on a same-subject conflict
 *                              (null = no resolution; the server rejects a conflict so the UI can
 *                              prompt, R5.1/R5.6)
 */
public record AddLineRequest(
        @NotNull ChangeType changeType,
        Long workItemId,
        Long roomId,
        Long workCategoryId,
        BigDecimal deltaQuantity,
        BigDecimal extraUnitPrice,
        Integer scheduleDeltaHalfDays,
        String description,
        MergeResolution mergeResolution) {
}
