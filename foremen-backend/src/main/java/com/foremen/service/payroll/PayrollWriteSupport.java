package com.foremen.service.payroll;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;

/**
 * FOR-07-06 — shared write-side helpers of {@link PayrollLineService} and
 * {@link PayrollProfileService}: the WORKER check (R8.7, same rule and code as
 * {@code WorkerPayoutService}) and a bespoke audit-row write with flat, cycle-free snapshots (the
 * entities hold a LAZY worker association, so they are never serialized as a graph).
 */
final class PayrollWriteSupport {

    static final String WORKER_INVALID = "error.workerPayout.worker.invalid";

    private static final String WORKER_ROLE_CODE = "WORKER";
    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    private PayrollWriteSupport() {
    }

    /** The worker must be an existing user with the {@code WORKER} role, else 422 {@link #WORKER_INVALID}. */
    static UserEntity assertWorker(UserDao userDao, Long workerId) {
        UserEntity user = workerId == null ? null : userDao.findById(workerId).orElse(null);
        RoleEntity role = user == null ? null : user.getRole();
        if (role == null || role.getCode() == null
                || !WORKER_ROLE_CODE.equalsIgnoreCase(role.getCode().trim())) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, WORKER_INVALID);
        }
        return user;
    }

    /** Writes one audit row in the caller's transaction. */
    static void audit(AuditLogDao auditLogDao, Class<?> entityClass, Long entityId, String operation,
                      String before, String after) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setEntityClass(entityClass.getSimpleName());
        auditLog.setEntityId(entityId);
        auditLog.setOperation(operation);
        auditLog.setPerformedBy(performingUser());
        auditLog.setPerformedAt(LocalDateTime.now());
        auditLog.setSnapshotBefore(before);
        auditLog.setSnapshotAfter(after);
        auditLogDao.save(auditLog);
    }

    /** JSON of a flat field map; never throws (falls back to the generic audit's error shape). */
    static String snapshot(Class<?> entityClass, Map<String, Object> fields) {
        try {
            return SNAPSHOT_MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            return "{\"error\":\"serialization_failed\",\"class\":\"" + entityClass.getSimpleName() + "\"}";
        }
    }

    private static String performingUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank()) {
            return auth.getName();
        }
        return "SYSTEM";
    }
}
