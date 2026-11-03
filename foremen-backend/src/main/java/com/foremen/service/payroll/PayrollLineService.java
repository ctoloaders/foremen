package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayrollLineDao;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayrollLineEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.payroll.dto.PayrollLineDto;
import com.foremen.service.payroll.dto.PayrollLineRequest;

/**
 * FOR-07-06 (Requirement 8; design §PayrollLineService, PayrollHintSelector) — CRUD of the worker's
 * additional payroll lines (free-text, signed one-off payments / deductions per month) and the
 * previous-month hints.
 *
 * <ul>
 *   <li>{@link #create} / {@link #update} — validation order: worker (R8.7), name (R8.2), amount
 *       (R8.3); {@code month} normalized to its first day (R8.1); name trimmed; amount scaled to 2
 *       decimals; audit {@code CREATE} / {@code UPDATE};</li>
 *   <li>{@link #delete} — hard delete with audit {@code DELETE}; unknown id → 404;</li>
 *   <li>{@link #hints} — the worker's M−1 lines whose normalized name is absent in M, duplicates
 *       collapsed to the latest (R8.5). Read-only: nothing is created (R8.6).</li>
 * </ul>
 *
 * <p>Additional lines never enter payout suggestions or distribution (R8.4) — this service has no
 * link to the payout services.
 */
@Service
@Transactional
public class PayrollLineService {

    static final String NOT_FOUND_MESSAGE = "error.entity.not.found";
    static final String NAME_REQUIRED = "error.payrollLine.name.required";
    static final String AMOUNT_INVALID = "error.payrollLine.amount.invalid";

    static final int NAME_MAX_LENGTH = 255;
    private static final int SCALE = 2;

    /** "Latest" order of entities for hint collapsing: {@code createdDate}, then {@code id}; nulls first. */
    static final Comparator<WorkerPayrollLineEntity> LATEST_LAST = Comparator
            .comparing(WorkerPayrollLineEntity::getCreatedDate,
                    Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
            .thenComparing(WorkerPayrollLineEntity::getId, Comparator.nullsFirst(Comparator.<Long>naturalOrder()));

    private final WorkerPayrollLineDao lineDao;
    private final UserDao userDao;
    private final AuditLogDao auditLogDao;

    public PayrollLineService(WorkerPayrollLineDao lineDao, UserDao userDao, AuditLogDao auditLogDao) {
        this.lineDao = lineDao;
        this.userDao = userDao;
        this.auditLogDao = auditLogDao;
    }

    /** Creates a line (R8.1–R8.3, R8.7). */
    public PayrollLineDto create(PayrollLineRequest req) {
        Validated v = validate(req);
        WorkerPayrollLineEntity line = new WorkerPayrollLineEntity();
        apply(line, v, req.note());
        WorkerPayrollLineEntity saved = lineDao.save(line);
        PayrollWriteSupport.audit(auditLogDao, WorkerPayrollLineEntity.class, saved.getId(), "CREATE",
                null, snapshot(saved));
        return PayrollLineDto.from(saved);
    }

    /** Updates a line (same validation as create); unknown id → 404. */
    public PayrollLineDto update(Long id, PayrollLineRequest req) {
        WorkerPayrollLineEntity line = find(id);
        Validated v = validate(req);
        String before = snapshot(line);
        apply(line, v, req.note());
        WorkerPayrollLineEntity saved = lineDao.save(line);
        PayrollWriteSupport.audit(auditLogDao, WorkerPayrollLineEntity.class, saved.getId(), "UPDATE",
                before, snapshot(saved));
        return PayrollLineDto.from(saved);
    }

    /** Hard-deletes a line; unknown id → 404. */
    public void delete(Long id) {
        WorkerPayrollLineEntity line = find(id);
        String before = snapshot(line);
        lineDao.delete(line);
        PayrollWriteSupport.audit(auditLogDao, WorkerPayrollLineEntity.class, id, "DELETE", before, null);
    }

    /**
     * Previous-month hints for {@code (worker, month)} (R8.5): lines of M−1 whose trimmed,
     * case-insensitive name does not exist in M; oldest first.
     *
     * @param workerId the worker (must be a WORKER, R8.7)
     * @param month    any day of month M
     */
    @Transactional(readOnly = true)
    public List<PayrollLineDto> hints(Long workerId, LocalDate month) {
        PayrollWriteSupport.assertWorker(userDao, workerId);
        LocalDate current = normalizeMonth(month == null ? LocalDate.now() : month);
        List<WorkerPayrollLineEntity> previous = lineDao.findByWorkerAndMonth(workerId, current.minusMonths(1));
        List<WorkerPayrollLineEntity> currentLines = lineDao.findByWorkerAndMonth(workerId, current);
        return PayrollHintSelector.hints(previous, currentLines, WorkerPayrollLineEntity::getName, LATEST_LAST)
                .stream().map(PayrollLineDto::from).toList();
    }

    /** First day of the month of {@code date}. */
    static LocalDate normalizeMonth(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    // --- internals ------------------------------------------------------------------------------

    private record Validated(UserEntity worker, LocalDate month, String name, BigDecimal amount) {
    }

    private Validated validate(PayrollLineRequest req) {
        if (req == null) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, PayrollWriteSupport.WORKER_INVALID);
        }
        UserEntity worker = PayrollWriteSupport.assertWorker(userDao, req.workerId());
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > NAME_MAX_LENGTH) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, NAME_REQUIRED);
        }
        BigDecimal amount = req.amount() == null ? null : req.amount().setScale(SCALE, RoundingMode.HALF_UP);
        if (amount == null || amount.signum() == 0) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, AMOUNT_INVALID);
        }
        LocalDate month = normalizeMonth(req.month() == null ? LocalDate.now() : req.month());
        return new Validated(worker, month, name, amount);
    }

    private static void apply(WorkerPayrollLineEntity line, Validated v, String note) {
        line.setWorker(v.worker());
        line.setMonth(v.month());
        line.setName(v.name());
        line.setAmount(v.amount());
        line.setNote(note == null || note.isBlank() ? null : note);
    }

    private WorkerPayrollLineEntity find(Long id) {
        return (id == null ? Optional.<WorkerPayrollLineEntity>empty() : lineDao.findById(id))
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, id));
    }

    private static String snapshot(WorkerPayrollLineEntity line) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", line.getId());
        fields.put("workerId", line.getWorker() == null ? null : line.getWorker().getId());
        fields.put("month", line.getMonth() == null ? null : line.getMonth().toString());
        fields.put("name", line.getName());
        fields.put("amount", line.getAmount() == null ? null : line.getAmount().toPlainString());
        fields.put("note", line.getNote());
        return PayrollWriteSupport.snapshot(WorkerPayrollLineEntity.class, fields);
    }
}
