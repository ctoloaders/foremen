package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayrollLineDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayrollLineEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.payroll.dto.PayrollLineDto;
import com.foremen.service.payroll.dto.PayrollLineRequest;

/**
 * FOR-07-06 task 5.3 — Mockito unit tests for {@link PayrollLineService} (Requirement 8): CRUD with
 * month normalization, name / amount / worker validation, audit on every write, and previous-month
 * hints via the real {@link PayrollHintSelector}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayrollLineServiceTest {

    private static final Long WORKER_ID = 700L;
    private static final Long LINE_ID = 41L;

    @Mock
    private WorkerPayrollLineDao lineDao;
    @Mock
    private UserDao userDao;
    @Mock
    private AuditLogDao auditLogDao;

    private PayrollLineService service;
    private UserEntity worker;

    @BeforeEach
    void setUp() {
        service = new PayrollLineService(lineDao, userDao, auditLogDao);
        worker = user(WORKER_ID, "WORKER");
        lenient().when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(worker));
        lenient().when(lineDao.save(any(WorkerPayrollLineEntity.class))).thenAnswer(inv -> {
            WorkerPayrollLineEntity l = inv.getArgument(0);
            if (l.getId() == null) {
                l.setId(LINE_ID);
            }
            return l;
        });
    }

    @Nested
    @DisplayName("create (R8.1–R8.3)")
    class Create {

        @Test
        void normalizesMonthTrimsNameScalesAmountAndAudits() {
            PayrollLineDto dto = service.create(new PayrollLineRequest(
                    WORKER_ID, LocalDate.of(2026, 7, 15), "  Multisport  ", new BigDecimal("150"), "card"));

            assertThat(dto.id()).isEqualTo(LINE_ID);
            assertThat(dto.workerId()).isEqualTo(WORKER_ID);
            assertThat(dto.month()).isEqualTo(LocalDate.of(2026, 7, 1));
            assertThat(dto.name()).isEqualTo("Multisport");
            assertThat(dto.amount()).isEqualByComparingTo("150").hasToString("150.00");
            assertThat(dto.note()).isEqualTo("card");

            AuditLogEntity audit = capturedAudit();
            assertThat(audit.getOperation()).isEqualTo("CREATE");
            assertThat(audit.getEntityClass()).isEqualTo("WorkerPayrollLineEntity");
            assertThat(audit.getEntityId()).isEqualTo(LINE_ID);
            assertThat(audit.getSnapshotBefore()).isNull();
            assertThat(audit.getSnapshotAfter()).contains("\"month\":\"2026-07-01\"").contains("\"amount\":\"150.00\"");
        }

        @Test
        void negativeAmountIsAllowed() {
            PayrollLineDto dto = service.create(new PayrollLineRequest(
                    WORKER_ID, LocalDate.of(2026, 7, 1), "ZUS", new BigDecimal("-320"), null));
            assertThat(dto.amount()).isEqualByComparingTo("-320");
        }

        @Test
        void zeroOrMissingAmountIsRejected() {
            assertApiError(() -> service.create(new PayrollLineRequest(
                    WORKER_ID, LocalDate.of(2026, 7, 1), "X", BigDecimal.ZERO, null)),
                    PayrollLineService.AMOUNT_INVALID);
            assertApiError(() -> service.create(new PayrollLineRequest(
                    WORKER_ID, LocalDate.of(2026, 7, 1), "X", new BigDecimal("0.001"), null)),
                    PayrollLineService.AMOUNT_INVALID);
            assertApiError(() -> service.create(new PayrollLineRequest(
                    WORKER_ID, LocalDate.of(2026, 7, 1), "X", null, null)),
                    PayrollLineService.AMOUNT_INVALID);
            verify(lineDao, never()).save(any());
        }

        @Test
        void blankMissingOrTooLongNameIsRejected() {
            for (String name : new String[] {null, "", "   ", "x".repeat(256)}) {
                assertApiError(() -> service.create(new PayrollLineRequest(
                        WORKER_ID, LocalDate.of(2026, 7, 1), name, BigDecimal.TEN, null)),
                        PayrollLineService.NAME_REQUIRED);
            }
            PayrollLineDto ok = service.create(new PayrollLineRequest(
                    WORKER_ID, LocalDate.of(2026, 7, 1), " " + "x".repeat(255) + " ", BigDecimal.TEN, null));
            assertThat(ok.name()).hasSize(255);
        }

        @Test
        void unknownOrNonWorkerIsRejected() {
            when(userDao.findById(1L)).thenReturn(Optional.of(user(1L, "MANAGER")));
            when(userDao.findById(2L)).thenReturn(Optional.empty());
            for (Long id : new Long[] {1L, 2L, null}) {
                assertApiError(() -> service.create(new PayrollLineRequest(
                        id, LocalDate.of(2026, 7, 1), "X", BigDecimal.TEN, null)),
                        PayrollWriteSupport.WORKER_INVALID);
            }
            verify(lineDao, never()).save(any());
            verify(auditLogDao, never()).save(any());
        }
    }

    @Nested
    @DisplayName("update / delete (R8.1)")
    class UpdateDelete {

        @Test
        void updateRevalidatesNormalizesAndAuditsBeforeAndAfter() {
            WorkerPayrollLineEntity existing = line(LINE_ID, LocalDate.of(2026, 7, 1), "Multisport", "150");
            when(lineDao.findById(LINE_ID)).thenReturn(Optional.of(existing));

            PayrollLineDto dto = service.update(LINE_ID, new PayrollLineRequest(
                    WORKER_ID, LocalDate.of(2026, 7, 31), "Multisport", new BigDecimal("160"), null));

            assertThat(dto.amount()).hasToString("160.00");
            assertThat(dto.month()).isEqualTo(LocalDate.of(2026, 7, 1));
            AuditLogEntity audit = capturedAudit();
            assertThat(audit.getOperation()).isEqualTo("UPDATE");
            assertThat(audit.getSnapshotBefore()).contains("\"amount\":\"150\"");
            assertThat(audit.getSnapshotAfter()).contains("\"amount\":\"160.00\"");
        }

        @Test
        void updateWithInvalidAmountChangesNothing() {
            WorkerPayrollLineEntity existing = line(LINE_ID, LocalDate.of(2026, 7, 1), "Multisport", "150");
            when(lineDao.findById(LINE_ID)).thenReturn(Optional.of(existing));

            assertApiError(() -> service.update(LINE_ID, new PayrollLineRequest(
                    WORKER_ID, LocalDate.of(2026, 7, 1), "Multisport", BigDecimal.ZERO, null)),
                    PayrollLineService.AMOUNT_INVALID);
            assertThat(existing.getAmount()).isEqualByComparingTo("150");
            verify(lineDao, never()).save(any());
        }

        @Test
        void unknownIdIs404() {
            when(lineDao.findById(99L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.update(99L, new PayrollLineRequest(
                    WORKER_ID, LocalDate.of(2026, 7, 1), "X", BigDecimal.TEN, null)))
                    .isInstanceOfSatisfying(ForemenApiException.class,
                            ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
            assertThatThrownBy(() -> service.delete(99L))
                    .isInstanceOfSatisfying(ForemenApiException.class,
                            ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
        }

        @Test
        void deleteRemovesAndAudits() {
            WorkerPayrollLineEntity existing = line(LINE_ID, LocalDate.of(2026, 7, 1), "Multisport", "150");
            when(lineDao.findById(LINE_ID)).thenReturn(Optional.of(existing));

            service.delete(LINE_ID);

            verify(lineDao).delete(existing);
            AuditLogEntity audit = capturedAudit();
            assertThat(audit.getOperation()).isEqualTo("DELETE");
            assertThat(audit.getEntityId()).isEqualTo(LINE_ID);
            assertThat(audit.getSnapshotBefore()).contains("Multisport");
            assertThat(audit.getSnapshotAfter()).isNull();
        }
    }

    @Nested
    @DisplayName("hints (R8.5–R8.7)")
    class Hints {

        @Test
        void previousMonthLinesNotPresentInCurrentMonthCollapsedToLatest() {
            LocalDate aug = LocalDate.of(2026, 8, 1);
            LocalDate jul = LocalDate.of(2026, 7, 1);
            WorkerPayrollLineEntity oldMulti = line(1L, jul, "Multisport", "150");
            oldMulti.setCreatedDate(LocalDateTime.of(2026, 7, 2, 10, 0));
            WorkerPayrollLineEntity newMulti = line(2L, jul, "multisport ", "160");
            newMulti.setCreatedDate(LocalDateTime.of(2026, 7, 20, 10, 0));
            WorkerPayrollLineEntity zus = line(3L, jul, "ZUS", "-320");
            zus.setCreatedDate(LocalDateTime.of(2026, 7, 5, 10, 0));
            when(lineDao.findByWorkerAndMonth(WORKER_ID, jul)).thenReturn(List.of(oldMulti, newMulti, zus));
            when(lineDao.findByWorkerAndMonth(WORKER_ID, aug)).thenReturn(List.of());

            List<PayrollLineDto> hints = service.hints(WORKER_ID, LocalDate.of(2026, 8, 17));

            assertThat(hints).extracting(PayrollLineDto::id).containsExactly(3L, 2L);
            assertThat(hints.get(1).amount()).isEqualByComparingTo("160");
            verify(lineDao, never()).save(any());
        }

        @Test
        void nameAlreadyInCurrentMonthIsNotHinted() {
            LocalDate aug = LocalDate.of(2026, 8, 1);
            LocalDate jul = LocalDate.of(2026, 7, 1);
            when(lineDao.findByWorkerAndMonth(WORKER_ID, jul)).thenReturn(List.of(
                    line(1L, jul, "Multisport", "160"), line(2L, jul, "ZUS", "-320")));
            when(lineDao.findByWorkerAndMonth(WORKER_ID, aug)).thenReturn(List.of(
                    line(5L, aug, "  MULTISPORT ", "160")));

            assertThat(service.hints(WORKER_ID, aug)).extracting(PayrollLineDto::name).containsExactly("ZUS");
        }

        @Test
        void januaryLooksAtPreviousDecember() {
            LocalDate dec = LocalDate.of(2025, 12, 1);
            when(lineDao.findByWorkerAndMonth(WORKER_ID, dec)).thenReturn(List.of(line(1L, dec, "Bonus", "50")));
            when(lineDao.findByWorkerAndMonth(WORKER_ID, LocalDate.of(2026, 1, 1))).thenReturn(List.of());

            assertThat(service.hints(WORKER_ID, LocalDate.of(2026, 1, 9))).hasSize(1);
        }

        @Test
        void nonWorkerIsRejected() {
            when(userDao.findById(3L)).thenReturn(Optional.of(user(3L, "FOREMAN")));
            assertApiError(() -> service.hints(3L, LocalDate.of(2026, 8, 1)), PayrollWriteSupport.WORKER_INVALID);
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private AuditLogEntity capturedAudit() {
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogDao).save(captor.capture());
        return captor.getValue();
    }

    private WorkerPayrollLineEntity line(Long id, LocalDate month, String name, String amount) {
        WorkerPayrollLineEntity l = new WorkerPayrollLineEntity();
        l.setId(id);
        l.setWorker(worker);
        l.setMonth(month);
        l.setName(name);
        l.setAmount(new BigDecimal(amount));
        return l;
    }

    private static UserEntity user(Long id, String roleCode) {
        UserEntity u = new UserEntity();
        u.setId(id);
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        u.setRole(role);
        return u;
    }

    private static void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ForemenApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
            assertThat(ex.getMessageCode()).isEqualTo(code);
        });
    }
}
