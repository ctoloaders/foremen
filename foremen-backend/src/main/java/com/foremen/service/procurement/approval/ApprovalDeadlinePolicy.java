package com.foremen.service.procurement.approval;

import com.foremen.exception.ForemenApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;

/**
 * Decision deadline rules (FOR-11-02, R6.4, R13.2, R14.1). "Today" is the calendar date in
 * {@code Europe/Warsaw}, so the day rolls over at Warsaw midnight.
 */
public final class ApprovalDeadlinePolicy {

    public static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    public static final String ERROR_INVALID = "error.positionApproval.deadline.invalid";

    private ApprovalDeadlinePolicy() {
    }

    /** Current date in Europe/Warsaw. */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /** A deadline is optional but, when present, must be {@code >= today} (400 otherwise). */
    public static void validate(LocalDate deadline, LocalDate today) {
        if (deadline != null && deadline.isBefore(today)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_INVALID);
        }
    }

    /** Derived, never stored: an OPEN round whose deadline is before today. */
    public static boolean isOverdue(ApprovalRoundStatus status, LocalDate deadline, LocalDate today) {
        return status == ApprovalRoundStatus.OPEN && deadline != null && deadline.isBefore(today);
    }
}
