package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayrollProfileDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayrollProfileEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.payroll.dto.PayrollProfileDto;

/**
 * FOR-07-06 task 5.3 — Mockito unit tests for {@link PayrollProfileService} (Requirement 9.1, 9.2):
 * default opening balance 0, upsert (insert vs update) with audit, validation of the value and the
 * worker.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayrollProfileServiceTest {

    private static final Long WORKER_ID = 700L;

    @Mock
    private WorkerPayrollProfileDao profileDao;
    @Mock
    private UserDao userDao;
    @Mock
    private AuditLogDao auditLogDao;

    private PayrollProfileService service;
    private UserEntity worker;

    @BeforeEach
    void setUp() {
        service = new PayrollProfileService(profileDao, userDao, auditLogDao);
        worker = user(WORKER_ID, "WORKER");
        lenient().when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(worker));
        lenient().when(profileDao.save(any(WorkerPayrollProfileEntity.class))).thenAnswer(inv -> {
            WorkerPayrollProfileEntity p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(77L);
            }
            return p;
        });
    }

    @Test
    void getDefaultsToZeroWithoutProfile() {
        when(profileDao.findByWorkerId(WORKER_ID)).thenReturn(Optional.empty());

        PayrollProfileDto dto = service.get(WORKER_ID);

        assertThat(dto.workerId()).isEqualTo(WORKER_ID);
        assertThat(dto.openingBalance()).hasToString("0.00");
    }

    @Test
    void getReturnsStoredValue() {
        when(profileDao.findByWorkerId(WORKER_ID)).thenReturn(Optional.of(profile(5L, "-250.5")));

        assertThat(service.get(WORKER_ID).openingBalance()).hasToString("-250.50");
    }

    @Test
    void setInsertsProfileWhenAbsentAndAuditsCreate() {
        when(profileDao.findByWorkerId(WORKER_ID)).thenReturn(Optional.empty());

        PayrollProfileDto dto = service.setOpeningBalance(WORKER_ID, new BigDecimal("1000"));

        assertThat(dto.openingBalance()).hasToString("1000.00");
        ArgumentCaptor<WorkerPayrollProfileEntity> saved = ArgumentCaptor.forClass(WorkerPayrollProfileEntity.class);
        verify(profileDao).save(saved.capture());
        assertThat(saved.getValue().getWorker()).isSameAs(worker);
        AuditLogEntity audit = capturedAudit();
        assertThat(audit.getOperation()).isEqualTo("CREATE");
        assertThat(audit.getEntityClass()).isEqualTo("WorkerPayrollProfileEntity");
        assertThat(audit.getEntityId()).isEqualTo(77L);
        assertThat(audit.getSnapshotBefore()).isNull();
        assertThat(audit.getSnapshotAfter()).contains("\"openingBalance\":\"1000.00\"");
    }

    @Test
    void setUpdatesExistingProfileAllowsNegativeAndAuditsUpdate() {
        WorkerPayrollProfileEntity existing = profile(5L, "1000.00");
        when(profileDao.findByWorkerId(WORKER_ID)).thenReturn(Optional.of(existing));

        PayrollProfileDto dto = service.setOpeningBalance(WORKER_ID, new BigDecimal("-42.1"));

        assertThat(dto.openingBalance()).hasToString("-42.10");
        assertThat(existing.getOpeningBalance()).hasToString("-42.10");
        AuditLogEntity audit = capturedAudit();
        assertThat(audit.getOperation()).isEqualTo("UPDATE");
        assertThat(audit.getEntityId()).isEqualTo(5L);
        assertThat(audit.getSnapshotBefore()).contains("\"openingBalance\":\"1000.00\"");
        assertThat(audit.getSnapshotAfter()).contains("\"openingBalance\":\"-42.10\"");
    }

    @Test
    void nullValueIsRejected() {
        assertThatThrownBy(() -> service.setOpeningBalance(WORKER_ID, null))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getMessageCode()).isEqualTo(PayrollProfileService.OPENING_BALANCE_INVALID);
                });
        verify(profileDao, never()).save(any());
        verify(auditLogDao, never()).save(any());
    }

    @Test
    void nonWorkerIsRejected() {
        when(userDao.findById(1L)).thenReturn(Optional.of(user(1L, "MANAGER")));
        when(userDao.findById(2L)).thenReturn(Optional.empty());
        for (Long id : new Long[] {1L, 2L}) {
            assertThatThrownBy(() -> service.setOpeningBalance(id, BigDecimal.ONE))
                    .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                        assertThat(ex.getStatus().value()).isEqualTo(422);
                        assertThat(ex.getMessageCode()).isEqualTo(PayrollWriteSupport.WORKER_INVALID);
                    });
            assertThatThrownBy(() -> service.get(id)).isInstanceOf(ForemenApiException.class);
        }
        verify(profileDao, never()).save(any());
    }

    private AuditLogEntity capturedAudit() {
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogDao).save(captor.capture());
        return captor.getValue();
    }

    private WorkerPayrollProfileEntity profile(Long id, String opening) {
        WorkerPayrollProfileEntity p = new WorkerPayrollProfileEntity();
        p.setId(id);
        p.setWorker(worker);
        p.setOpeningBalance(new BigDecimal(opening));
        return p;
    }

    private static UserEntity user(Long id, String roleCode) {
        UserEntity u = new UserEntity();
        u.setId(id);
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        u.setRole(role);
        return u;
    }
}
