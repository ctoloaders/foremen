package com.foremen.dao.model;

/**
 * FOR-06-08 (Requirement 11.1): the kind of delta carried by an {@code AmendmentLineEntity}. A single
 * polymorphic line row is discriminated by this type, populating only the fields relevant to it:
 *
 * <ul>
 *   <li>{@link #SCHEDULE_CHANGE} — a deadline delta. Populates {@code scheduleDeltaHalfDays} (signed
 *       half-days; positive = extend, negative = shorten) and optionally {@code workCategory} (null =
 *       whole-project schedule shift).</li>
 *   <li>{@link #ADDITIONAL_WORK} — a brand-new work item not on the signed estimate. Populates {@code
 *       workItem}, {@code room}, {@code deltaQuantity} (added volume, positive), {@code extraUnitPrice}
 *       (the full new unit price), and optionally {@code description}.</li>
 *   <li>{@link #VOLUME_CHANGE} — a quantity change of an existing estimate line. Populates {@code
 *       workItem}, {@code room}, {@code deltaQuantity} (signed; positive = add, negative = remove), and
 *       {@code extraUnitPrice} (the unit price applied to the delta volume).</li>
 * </ul>
 *
 * <p>Stored as a string ({@code @Enumerated(EnumType.STRING)}); the display label is resolved on the
 * frontend via the {@code amendments.changeType.*} i18n keys (no backend i18n, mirroring {@link
 * DocumentStatus}).
 */
public enum ChangeType {
    SCHEDULE_CHANGE,
    ADDITIONAL_WORK,
    VOLUME_CHANGE
}
