package com.foremen.service.payroll;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.UserDao;
import com.foremen.service.payroll.dto.PayoutLineDto;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;

/**
 * FOR-07-06 Round 1 / bug H — previous-month <b>payout-line</b> hints for the create-payment dialog.
 *
 * <p>Distinct from the additional-free-text-line hints of {@link PayrollLineService#hints} (which
 * suggest {@code WorkerPayrollLineEntity} rows): this service suggests the worker's <b>previous
 * calendar month</b> payable work-report lines — the unpaid {@code APPROVED} report lines whose
 * report ends on or before the previous month end — so the issuer can quick-add last month's line
 * items when creating a new payment.
 *
 * <p>It reuses the existing worker-wide, non-advance suggestion of {@link PayoutSuggestionService}
 * (the very same candidate selection the create dialog previews and distributes) for the previous
 * month's window {@code [firstDay(M-1), lastDay(M-1)]}, where {@code M} is the month of the
 * {@code month} argument (today when omitted). Only <b>available</b> lines (resolvable cost) are
 * returned — an unavailable line has no amount to quick-add. Read-only: nothing is created (mirrors
 * the hint contract of R8.6).
 *
 * <p>Access is gated by the {@code PAYROLL} READ grant on {@link com.foremen.controller.PayrollController}
 * and the worker must be a {@code WORKER} (R8.7). No project is involved (payouts are worker-wide).
 */
@Service
public class PayoutLineHintService {

    private final UserDao userDao;
    private final PayoutSuggestionService suggestionService;

    private Clock clock = Clock.systemDefaultZone();

    public PayoutLineHintService(UserDao userDao, PayoutSuggestionService suggestionService) {
        this.userDao = userDao;
        this.suggestionService = suggestionService;
    }

    /** Test seam for "today" (resolving the previous month). */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The worker's previous-month payable line hints relative to {@code month} (bug H).
     *
     * @param workerId the worker (must be a {@code WORKER}, else 422 {@code error.workerPayout.worker.invalid})
     * @param month    any day of the reference month {@code M}; {@code null} ⇒ the current month
     * @return the previous month's available payable lines in distribution order (oldest first),
     *         empty when the worker has none
     */
    @Transactional(readOnly = true)
    public List<PayoutLineDto> previousMonthHints(Long workerId, LocalDate month) {
        PayrollWriteSupport.assertWorker(userDao, workerId);
        YearMonth reference = YearMonth.from(month == null ? LocalDate.now(clock) : month);
        YearMonth previous = reference.minusMonths(1);
        LocalDate from = previous.atDay(1);
        LocalDate to = previous.atEndOfMonth();

        PayoutSuggestionDto suggestion = suggestionService.suggest(workerId, from, to, false);
        return suggestion.breakdown().stream()
                .filter(PayoutLineDto::available)
                .toList();
    }
}
