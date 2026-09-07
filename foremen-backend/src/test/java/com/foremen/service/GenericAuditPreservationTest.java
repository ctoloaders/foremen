package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.foremen.dao.AdminDao;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import jakarta.persistence.EntityManager;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * PRESERVATION TEST — FOR-04-bugs Requirement 3.7 (existing generic audit unchanged).
 *
 * <p>Captures the BASELINE audit behavior of the generic {@link AdminService} CRUD defaults that
 * MUST remain unchanged after the Bug 6 backend fix (task 10). It is expected to <b>PASS on the
 * current UNFIXED code</b> — passing confirms the behavior we want to preserve: a generic
 * {@code create(...)} writes exactly one {@link AuditLogEntity} with {@code operation = "CREATE"},
 * and {@code update(...)} writes exactly one with {@code operation = "UPDATE"}.
 *
 * <p>The test drives a minimal in-file {@link AdminService} implementation
 * ({@link StubAdminService}) over a trivial {@link StubEntity}, with Mockito mocks for the four
 * collaborators the create/update defaults touch ({@code getDao} → also serves as read/write dao,
 * {@code getEntityManager}, {@code getAuditLogDao}, {@code getMapper}). No Spring context and no
 * Testcontainers stack, so it runs fast.
 *
 * Feature: FOR-04-bugs, Bug 6 preservation (generic audit)
 * Validates: Requirements 3.7
 */
class GenericAuditPreservationTest {

    /** Trivial audited entity — only an {@code id} matters for audit extraction. */
    static class StubEntity {
        Long id;
        String name;
    }

    /** Trivial service model carried by the generic contract (unused fields). */
    static class StubModel {
        String name;
    }

    /**
     * Minimal concrete {@link AdminService} wiring the four collaborators the CREATE/UPDATE defaults
     * use. Everything else inherits the interface defaults.
     */
    static class StubAdminService
            implements AdminService<StubModel, StubModel, StubEntity, Long> {

        private final AdminDao<StubEntity, Long> dao;
        private final AuditLogDao auditLogDao;
        private final EntityManager entityManager;
        private final ServiceToDaoMapper<StubEntity, StubModel, StubModel> mapper;

        StubAdminService(AdminDao<StubEntity, Long> dao,
                         AuditLogDao auditLogDao,
                         EntityManager entityManager,
                         ServiceToDaoMapper<StubEntity, StubModel, StubModel> mapper) {
            this.dao = dao;
            this.auditLogDao = auditLogDao;
            this.entityManager = entityManager;
            this.mapper = mapper;
        }

        @Override
        public AdminDao<StubEntity, Long> getDao() {
            return dao;
        }

        @Override
        public AuditLogDao getAuditLogDao() {
            return auditLogDao;
        }

        @Override
        public ServiceToDaoMapper<StubEntity, StubModel, StubModel> getMapper() {
            return mapper;
        }

        @Override
        public EntityManager getEntityManager() {
            return entityManager;
        }

        @Override
        public Class<StubEntity> getDaoModelClass() {
            return StubEntity.class;
        }
    }

    @SuppressWarnings("unchecked")
    private static StubAdminService newService(AuditLogDao auditLogDao,
                                               AdminDao<StubEntity, Long> dao,
                                               ServiceToDaoMapper<StubEntity, StubModel, StubModel> mapper) {
        EntityManager entityManager = mock(EntityManager.class);
        return new StubAdminService(dao, auditLogDao, entityManager, mapper);
    }

    @Test
    @DisplayName("generic create(...) writes exactly one CREATE audit row (baseline, must not change)")
    @SuppressWarnings("unchecked")
    void genericCreateWritesCreateAuditRow() {
        AuditLogDao auditLogDao = mock(AuditLogDao.class);
        AdminDao<StubEntity, Long> dao = mock(AdminDao.class);
        ServiceToDaoMapper<StubEntity, StubModel, StubModel> mapper = mock(ServiceToDaoMapper.class);

        StubEntity created = new StubEntity();
        created.id = 7L;
        created.name = "created";
        when(mapper.toCreateDaoModel(any())).thenReturn(created);
        when(dao.save(any(StubEntity.class))).thenReturn(created);
        when(mapper.toServiceExtendedModel(any(StubEntity.class))).thenReturn(new StubModel());

        StubAdminService service = newService(auditLogDao, dao, mapper);

        service.create(new StubModel());

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogDao, times(1)).save(captor.capture());

        AuditLogEntity row = captor.getValue();
        assertThat(row.getOperation()).isEqualTo("CREATE");
        assertThat(row.getEntityClass()).isEqualTo("StubEntity");
        assertThat(row.getEntityId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("generic update(...) writes exactly one UPDATE audit row (baseline, must not change)")
    @SuppressWarnings("unchecked")
    void genericUpdateWritesUpdateAuditRow() {
        AuditLogDao auditLogDao = mock(AuditLogDao.class);
        AdminDao<StubEntity, Long> dao = mock(AdminDao.class);
        ServiceToDaoMapper<StubEntity, StubModel, StubModel> mapper = mock(ServiceToDaoMapper.class);

        StubEntity existing = new StubEntity();
        existing.id = 11L;
        existing.name = "before";
        when(dao.findById(11L)).thenReturn(java.util.Optional.of(existing));
        when(dao.save(any(StubEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toServiceExtendedModel(any(StubEntity.class))).thenReturn(new StubModel());
        // updateFields + getI18nSupportedProperties are void/no-op for this stub; leave as mock defaults.
        when(mapper.getI18nSupportedProperties()).thenReturn(Set.of());

        StubAdminService service = newService(auditLogDao, dao, mapper);

        service.update(11L, new StubModel());

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogDao, times(1)).save(captor.capture());

        AuditLogEntity row = captor.getValue();
        assertThat(row.getOperation()).isEqualTo("UPDATE");
        assertThat(row.getEntityClass()).isEqualTo("StubEntity");
        assertThat(row.getEntityId()).isEqualTo(11L);
    }
}
