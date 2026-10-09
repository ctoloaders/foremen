package com.foremen.service;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.exception.ForemenApiException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FOR-05-09 task 15.3 (Requirement 14 criteria 10/11) — unit tests for the FOR-05-06 Worker_Type
 * hard-delete guard added to {@link WorkerTypeService#deleteById(Long)}.
 *
 * <p>A Worker_Type still referenced by any Project_Member cannot be hard-deleted: the service
 * rejects the deletion with HTTP 409 {@code error.worker.type.in.use} <em>before</em> touching the
 * generic delete path, so neither the audit DELETE row nor the row removal runs and the Worker_Type
 * and its referencing members stay unchanged. When no Project_Member references the type, the guard
 * falls through to the inherited {@link AdminService#deleteById(Object)}, which removes the row.
 */
@ExtendWith(MockitoExtension.class)
class WorkerTypeServiceDeleteGuardTest {

    @Mock
    private WorkerTypeDao dao;
    @Mock
    private com.foremen.service.model.mapper.WorkerTypeServiceMapper mapper;
    @Mock
    private com.foremen.service.audit.AuditLogDao auditLogDao;
    @Mock
    private EntityManager entityManager;
    @Mock
    private ProjectMemberDao projectMemberDao;

    @InjectMocks
    private WorkerTypeService service;

    private static final Long WORKER_TYPE_ID = 11L;

    @Test
    @DisplayName("deleteById raises 409 error.worker.type.in.use for a referenced Worker_Type and removes nothing")
    void deleteReferencedWorkerTypeRaisesConflictAndRemovesNothing() {
        // The type is referenced by at least one Project_Member -> the guard trips.
        when(projectMemberDao.existsByWorkerTypeId(WORKER_TYPE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteById(WORKER_TYPE_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.worker.type.in.use");
                });

        // Rejected before the generic delete path: no row lookup, no audit row, no row removal.
        verify(dao, never()).findById(anyLong());
        verify(dao, never()).deleteById(anyLong());
        verify(auditLogDao, never()).save(any());
    }

    @Test
    @DisplayName("deleteById falls through to the generic delete when no Project_Member references the Worker_Type")
    void deleteUnreferencedWorkerTypeFallsThroughToGenericDelete() {
        WorkerTypeEntity type = new WorkerTypeEntity();
        type.setId(WORKER_TYPE_ID);
        type.setCode("GENERAL");
        // No Project_Member references the type -> the guard does not trip.
        when(projectMemberDao.existsByWorkerTypeId(WORKER_TYPE_ID)).thenReturn(false);
        when(dao.findById(WORKER_TYPE_ID)).thenReturn(Optional.of(type));

        service.deleteById(WORKER_TYPE_ID);

        // The guard was evaluated, then the inherited AdminService.deleteById removed the row and
        // wrote the DELETE audit row.
        verify(projectMemberDao).existsByWorkerTypeId(WORKER_TYPE_ID);
        verify(dao).deleteById(WORKER_TYPE_ID);
        verify(auditLogDao).save(any());
        verify(entityManager).flush();
    }
}
