package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayrollProfileDao;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayrollProfileEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.payroll.dto.PayrollProfileDto;

/**
 * FOR-07-06 (Requirement 9.1, 9.2; design §PayrollProfileService) — the worker's opening balance.
 *
 * <ul>
 *   <li>{@link #get} — the opening balance, {@code 0} when the worker has no profile row (R9.1);</li>
 *   <li>{@link #setOpeningBalance} — upserts the single profile row (any number, may be negative;
 *       {@code null} → 422 {@code error.payrollProfile.openingBalance.invalid}, R9.2); audit
 *       {@code CREATE} on insert, {@code UPDATE} on change.</li>
 * </ul>
 * Both reject an unknown / non-WORKER worker with 422 {@code error.workerPayout.worker.invalid}.
 * No balance is persisted here — balances are computed on read (R9.4).
 */
@Service
@Transactional
public class PayrollProfileService {

    static final String OPENING_BALANCE_INVALID = "error.payrollProfile.openingBalance.invalid";

    private static final int SCALE = 2;

    private final WorkerPayrollProfileDao profileDao;
    private final UserDao userDao;
    private final AuditLogDao auditLogDao;

    public PayrollProfileService(WorkerPayrollProfileDao profileDao, UserDao userDao, AuditLogDao auditLogDao) {
        this.profileDao = profileDao;
        this.userDao = userDao;
        this.auditLogDao = auditLogDao;
    }

    /** The worker's opening balance, {@code 0.00} when no profile exists (R9.1). */
    @Transactional(readOnly = true)
    public PayrollProfileDto get(Long workerId) {
        PayrollWriteSupport.assertWorker(userDao, workerId);
        BigDecimal opening = profileDao.findByWorkerId(workerId)
                .map(WorkerPayrollProfileEntity::getOpeningBalance)
                .orElse(BigDecimal.ZERO);
        return new PayrollProfileDto(workerId, scale(opening));
    }

    /** Upserts the worker's opening balance (R9.2). */
    public PayrollProfileDto setOpeningBalance(Long workerId, BigDecimal value) {
        UserEntity worker = PayrollWriteSupport.assertWorker(userDao, workerId);
        if (value == null) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, OPENING_BALANCE_INVALID);
        }
        BigDecimal opening = scale(value);
        WorkerPayrollProfileEntity profile = profileDao.findByWorkerId(workerId).orElse(null);
        String operation;
        String before;
        if (profile == null) {
            profile = new WorkerPayrollProfileEntity();
            profile.setWorker(worker);
            operation = "CREATE";
            before = null;
        } else {
            operation = "UPDATE";
            before = snapshot(profile, workerId);
        }
        profile.setOpeningBalance(opening);
        WorkerPayrollProfileEntity saved = profileDao.save(profile);
        PayrollWriteSupport.audit(auditLogDao, WorkerPayrollProfileEntity.class, saved.getId(), operation,
                before, snapshot(saved, workerId));
        return new PayrollProfileDto(workerId, scale(saved.getOpeningBalance()));
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static String snapshot(WorkerPayrollProfileEntity profile, Long workerId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", profile.getId());
        fields.put("workerId", workerId);
        fields.put("openingBalance",
                profile.getOpeningBalance() == null ? null : profile.getOpeningBalance().toPlainString());
        return PayrollWriteSupport.snapshot(WorkerPayrollProfileEntity.class, fields);
    }
}
