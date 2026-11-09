package com.foremen.service.dashboard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * FOR-10 (Requirement 15.3) — seeds default widgets for a freshly created user. Runs after the
 * creating transaction commits, in its own {@code REQUIRES_NEW} transaction; any failure is logged and
 * swallowed so user creation never fails because of the dashboard (lazy seeding on first read retries).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserCreatedDashboardListener {

    private final DashboardDefaultsService defaultsService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserCreated(UserCreatedEvent event) {
        if (event == null || event.userId() == null) {
            return;
        }
        try {
            defaultsService.seedFor(event.userId());
        } catch (RuntimeException e) {
            log.warn("Dashboard default seeding failed for user {}: {}", event.userId(), e.toString());
        }
    }
}
