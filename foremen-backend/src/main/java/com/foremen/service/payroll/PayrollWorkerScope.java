package com.foremen.service.payroll;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;

import com.foremen.config.security.CallerContext;
import com.foremen.exception.ForemenApiException;

/**
 * Narrows payroll reads for the WORKER role to the caller's own data (FOR-07-06 bug-report Round 2):
 * a WORKER sees only rows where {@code worker = current user}. Every other role is unaffected.
 *
 * <p>The generic payout list goes through {@link WorkerPayoutService#addRequiredQuery()}; the
 * worker-keyed reads (company table, worker card, hints, payout hints, suggest) use this helper.
 */
public final class PayrollWorkerScope {

    static final String NOT_FOUND_MESSAGE = "error.entity.not.found";

    private PayrollWorkerScope() {
    }

    /**
     * For a WORKER caller, refuses any {@code workerId} other than the caller's own with 404 (the
     * other worker is indistinguishable from a missing one). No-op for every other role.
     */
    public static void assertMayRead(Long workerId) {
        if (!CallerContext.isWorker()) {
            return;
        }
        Long callerId = CallerContext.currentUserId();
        if (callerId == null || !Objects.equals(callerId, workerId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, workerId);
        }
    }

    /**
     * The worker selection a read may use: for a WORKER caller always exactly the caller (whatever was
     * requested); for every other role the requested selection unchanged.
     */
    public static Collection<Long> narrow(Collection<Long> workerIds) {
        if (!CallerContext.isWorker()) {
            return workerIds;
        }
        Long callerId = CallerContext.currentUserId();
        // An unresolvable caller gets an id that matches no user, so the table is empty.
        return List.of(callerId == null ? -1L : callerId);
    }
}
