package com.foremen.service.execution.amendment;

import java.math.BigDecimal;
import java.util.Objects;

import com.foremen.dao.model.ChangeType;

/**
 * FOR-06-08 (Requirement 5, 7) — the persistence-free, service-model representation of a single
 * amendment change line that {@link AmendmentMergeResolver} operates on.
 *
 * <p>This deliberately mirrors the (soon-to-exist) {@code AmendmentLineEntity} fields but carries no
 * JPA / Spring coupling, so the merge arithmetic (Requirement 5) is a side-effect-free function
 * directly jqwik-testable with no persistence context. The {@code AmendmentService} (task 5.1) maps
 * between its entities and this value type around the resolver.
 *
 * <p>A single polymorphic line is discriminated by {@link #changeType}, populating only the fields
 * relevant to it (see {@link ChangeType}):
 *
 * <ul>
 *   <li>{@link ChangeType#SCHEDULE_CHANGE} — {@link #scheduleDeltaHalfDays} (signed) and optional
 *       {@link #workCategoryId}; the cost fields are {@code null}.</li>
 *   <li>{@link ChangeType#ADDITIONAL_WORK} — {@link #workItemId}, {@link #roomId},
 *       {@link #deltaQuantity} ({@literal >} 0), {@link #extraUnitPrice}.</li>
 *   <li>{@link ChangeType#VOLUME_CHANGE} — {@link #workItemId}, {@link #roomId},
 *       {@link #deltaQuantity} (signed), {@link #extraUnitPrice}; {@link #baselineQuantity} is the
 *       read-only signed-estimate quantity the resulting-volume (non-negative) guard is checked
 *       against (Requirement 5.3 / 3.6).</li>
 * </ul>
 *
 * <p>The object is mutable because {@code REPLACE} / {@code ADD_TO} resolve the surviving line
 * <em>in place</em> (Requirement 5.4).
 */
public final class AmendmentLineDelta {

    private final ChangeType changeType;
    private Long workItemId;
    private Long roomId;
    private Long workCategoryId;
    private BigDecimal deltaQuantity;
    private BigDecimal extraUnitPrice;
    private Integer scheduleDeltaHalfDays;

    /**
     * The read-only signed-estimate quantity of this {@code (workItem, room)} line, used only to check
     * that a {@code VOLUME_CHANGE} never drives the resulting volume ({@code baselineQuantity +
     * deltaQuantity}) negative (Requirement 3.6 / 5.3). {@code null} for schedule / additional-work
     * lines (an additional work has no pre-existing baseline, so its own non-negative {@code
     * deltaQuantity} is the resulting volume).
     */
    private BigDecimal baselineQuantity;

    public AmendmentLineDelta(ChangeType changeType) {
        this.changeType = Objects.requireNonNull(changeType, "changeType");
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public Long getWorkItemId() {
        return workItemId;
    }

    public AmendmentLineDelta setWorkItemId(Long workItemId) {
        this.workItemId = workItemId;
        return this;
    }

    public Long getRoomId() {
        return roomId;
    }

    public AmendmentLineDelta setRoomId(Long roomId) {
        this.roomId = roomId;
        return this;
    }

    public Long getWorkCategoryId() {
        return workCategoryId;
    }

    public AmendmentLineDelta setWorkCategoryId(Long workCategoryId) {
        this.workCategoryId = workCategoryId;
        return this;
    }

    public BigDecimal getDeltaQuantity() {
        return deltaQuantity;
    }

    public AmendmentLineDelta setDeltaQuantity(BigDecimal deltaQuantity) {
        this.deltaQuantity = deltaQuantity;
        return this;
    }

    public BigDecimal getExtraUnitPrice() {
        return extraUnitPrice;
    }

    public AmendmentLineDelta setExtraUnitPrice(BigDecimal extraUnitPrice) {
        this.extraUnitPrice = extraUnitPrice;
        return this;
    }

    public Integer getScheduleDeltaHalfDays() {
        return scheduleDeltaHalfDays;
    }

    public AmendmentLineDelta setScheduleDeltaHalfDays(Integer scheduleDeltaHalfDays) {
        this.scheduleDeltaHalfDays = scheduleDeltaHalfDays;
        return this;
    }

    public BigDecimal getBaselineQuantity() {
        return baselineQuantity;
    }

    public AmendmentLineDelta setBaselineQuantity(BigDecimal baselineQuantity) {
        this.baselineQuantity = baselineQuantity;
        return this;
    }
}
