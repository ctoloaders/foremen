package com.foremen.service.dashboard;

/**
 * FOR-10 (Requirement 15.3) — published after a user row is persisted through any creation path.
 * Consumed after commit by {@link UserCreatedDashboardListener} to seed the user's default dashboard.
 */
public record UserCreatedEvent(Long userId) {
}
