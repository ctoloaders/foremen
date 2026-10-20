package com.foremen.controller.model;

import java.math.BigDecimal;

import com.foremen.dao.model.ChangeType;

/**
 * FOR-06-08 (Requirements 1.4, 3.1; design §Data model 2, §Frontend) — the per-line sub-DTO of an
 * amendment, carried on the read path (an amendment's resolved change lines) and echoed on the write
 * path. A single polymorphic row discriminated by {@link #changeType}, populating only the fields
 * relevant to its type (see {@link ChangeType}).
 *
 * <ul>
 *   <li>{@link ChangeType#SCHEDULE_CHANGE} — {@link #scheduleDeltaHalfDays} (signed half-days) and
 *       optionally {@link #workCategoryId} (null = whole-project schedule shift).</li>
 *   <li>{@link ChangeType#ADDITIONAL_WORK} — {@link #workItemId}, {@link #roomId},
 *       {@link #deltaQuantity} (added volume, positive), {@link #extraUnitPrice} (the new unit
 *       price), and optionally {@link #description}.</li>
 *   <li>{@link ChangeType#VOLUME_CHANGE} — {@link #workItemId}, {@link #roomId},
 *       {@link #deltaQuantity} (signed), {@link #extraUnitPrice} (the unit price on the delta
 *       volume).</li>
 * </ul>
 *
 * <p>On the read path the {@code *Name} fields carry the referenced work item / room / work
 * category localized display names (ru → pl → code/label, R11.1/R11.2), resolved in the service
 * mapper; on the write path (an {@link AddLineRequest}) they are left unset — the service resolves
 * the FKs. Each line stores a DELTA against the signed baseline, never an absolute value (R7.1).
 *
 * @param id                    the persisted line id (read path; {@code null} on a write-path row)
 * @param changeType            the line's change type (the discriminator)
 * @param workItemId            ADDITIONAL_WORK / VOLUME_CHANGE target work item (null for schedule)
 * @param workItemName          the work item's localized display name (read path)
 * @param roomId                ADDITIONAL_WORK / VOLUME_CHANGE target room (null for schedule)
 * @param roomName              the room's localized display name (read path)
 * @param workCategoryId        SCHEDULE_CHANGE target category (null = whole project)
 * @param workCategoryName      the category's localized display name (read path)
 * @param deltaQuantity         signed volume delta (volume/additional-work); null for schedule
 * @param extraUnitPrice        the new unit price (the Effective_Date_Seam field); null for schedule
 * @param scheduleDeltaHalfDays signed half-day schedule delta (SCHEDULE_CHANGE); null for cost lines
 * @param description           free text, untranslated
 */
public record AmendmentLineDto(
        Long id,
        ChangeType changeType,
        Long workItemId,
        String workItemName,
        Long roomId,
        String roomName,
        Long workCategoryId,
        String workCategoryName,
        BigDecimal deltaQuantity,
        BigDecimal extraUnitPrice,
        Integer scheduleDeltaHalfDays,
        String description) {
}
