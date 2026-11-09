package com.foremen.service.dashboard;

/**
 * FOR-10 (Requirement 17.2) — published by {@code DashboardWidgetService#deactivate} once a library
 * widget has been switched {@code active: true → false}. Consumed after commit by
 * {@link DashboardNotificationEmitter}, so a notification failure never rolls back the deactivation.
 */
public record DashboardWidgetDeactivatedEvent(Long widgetId) {
}
