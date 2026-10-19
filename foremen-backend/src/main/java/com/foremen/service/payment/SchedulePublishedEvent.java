package com.foremen.service.payment;

/**
 * FOR-06-07 (Requirement 9.2 row 5, 4.3): the transition event raised when a schedule is published
 * ({@code DRAFT → PUBLISHED}). Published by {@code PaymentScheduleService#publish} once the schedule
 * has durably transitioned, and consumed by the best-effort {@code PaymentNotificationEmitter}
 * (task 5.6) via an {@code AFTER_COMMIT} {@code @TransactionalEventListener}, so an emission failure
 * never rolls back the publish transition (R9.5).
 *
 * <p>Unlike the other five payment triggers (which notify MANAGER + FINANCIER), this one notifies the
 * project's <b>CLIENT</b> member(s); its deep-link targets the FOR-09 client surface rather than the
 * internal Płatności tab (R9.2). A plain immutable record carrying only ids — the emitter resolves the
 * CLIENT recipient(s) and the FOR-09 deep-link from these ids.
 *
 * @param scheduleId the schedule that was published
 * @param projectId  the owning project id (used to resolve the CLIENT recipient(s) + FOR-09 deep-link)
 */
public record SchedulePublishedEvent(Long scheduleId, Long projectId) {
}
