package com.foremen.service.audit.property;

import com.foremen.dao.AdminDao;
import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.AdminService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import jakarta.persistence.EntityManager;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Property 7: Universal Audit Records All Write Operations
 *
 * For any entity and for any write operation (CREATE, UPDATE, DELETE, SOFT_DELETE),
 * the saveAudit method SHALL persist an AuditLogEntity with:
 * - entityClass equal to the entity's simple class name
 * - entityId equal to the entity's ID (extracted via reflection)
 * - operation equal to the operation string parameter
 * - performedBy equal to the current authenticated user (or "SYSTEM" if unauthenticated)
 * - performedAt equal to a timestamp within 1 second of the operation time
 * - snapshotBefore/snapshotAfter containing JSON serializations of before/after entity states
 *
 * Validates: Requirements 15.1, 15.2, 15.3
 */
@Tag("Feature: FOR-01-06-crud-service, Property 7: Universal Audit Records All Write Operations")
class UniversalAuditPropertyTest {

    // --- Test entity classes ---

    static class TestEntity {
        private Long id;

        public TestEntity(Long id) {
            this.id = id;
        }
    }

    static class OrderEntity {
        private Long id;

        public OrderEntity(Long id) {
            this.id = id;
        }
    }

    static class ProjectEntity {
        private Long id;

        public ProjectEntity(Long id) {
            this.id = id;
        }
    }

    static class InvoiceEntity {
        private Long id;

        public InvoiceEntity(Long id) {
            this.id = id;
        }
    }

    // --- Capturing AuditLogDao via Mockito ---

    private final List<AuditLogEntity> capturedAuditLogs = new ArrayList<>();
    private final AuditLogDao mockAuditLogDao = createMockAuditLogDao();

    private AuditLogDao createMockAuditLogDao() {
        AuditLogDao mock = Mockito.mock(AuditLogDao.class);
        doAnswer(invocation -> {
            AuditLogEntity entity = invocation.getArgument(0);
            capturedAuditLogs.add(entity);
            return entity;
        }).when(mock).save(any(AuditLogEntity.class));
        return mock;
    }

    // --- Minimal AdminService implementation for testing ---

    @SuppressWarnings("unchecked")
    private <T> AdminService<Object, Object, T, Long> createService() {
        return new AdminService<>() {
            @Override
            public AdminDao<T, Long> getDao() { return null; }

            @Override
            public AuditLogDao getAuditLogDao() { return mockAuditLogDao; }

            @Override
            public ServiceToDaoMapper<T, Object, Object> getMapper() { return null; }

            @Override
            public ReadOnlyAdminDao<T, Long> getReadDao() { return null; }

            @Override
            public EntityManager getEntityManager() { return null; }
        };
    }

    @BeforeProperty
    void setUp() {
        capturedAuditLogs.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterProperty
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- Property: entityClass equals entity's simple class name ---

    @Property(tries = 100)
    void entityClassEqualsSimpleClassName(
            @ForAll("testEntities") Object entity,
            @ForAll("operations") String operation
    ) {
        AdminService<Object, Object, Object, Long> service = createService();
        capturedAuditLogs.clear();

        service.saveAudit(null, entity, operation);

        assertThat(capturedAuditLogs).hasSize(1);
        AuditLogEntity auditLog = capturedAuditLogs.getFirst();
        assertThat(auditLog.getEntityClass()).isEqualTo(entity.getClass().getSimpleName());
    }

    // --- Property: entityId equals entity's id field value ---

    @Property(tries = 100)
    void entityIdEqualsEntityIdField(
            @ForAll("entityIds") Long entityId,
            @ForAll("operations") String operation
    ) {
        AdminService<Object, Object, Object, Long> service = createService();
        capturedAuditLogs.clear();

        TestEntity entity = new TestEntity(entityId);
        service.saveAudit(null, entity, operation);

        assertThat(capturedAuditLogs).hasSize(1);
        AuditLogEntity auditLog = capturedAuditLogs.getFirst();
        assertThat(auditLog.getEntityId()).isEqualTo(entityId);
    }

    // --- Property: operation equals the passed operation string ---

    @Property(tries = 100)
    void operationEqualsPassedOperationString(
            @ForAll("testEntities") Object entity,
            @ForAll("operations") String operation
    ) {
        AdminService<Object, Object, Object, Long> service = createService();
        capturedAuditLogs.clear();

        service.saveAudit(null, entity, operation);

        assertThat(capturedAuditLogs).hasSize(1);
        AuditLogEntity auditLog = capturedAuditLogs.getFirst();
        assertThat(auditLog.getOperation()).isEqualTo(operation);
    }

    // --- Property: performedBy is non-null and equals authenticated user ---

    @Property(tries = 100)
    void performedByEqualsAuthenticatedUser(
            @ForAll("testEntities") Object entity,
            @ForAll("operations") String operation,
            @ForAll("userNames") String userName
    ) {
        AdminService<Object, Object, Object, Long> service = createService();
        capturedAuditLogs.clear();

        // Set up authentication context
        TestingAuthenticationToken auth = new TestingAuthenticationToken(userName, "password");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        service.saveAudit(null, entity, operation);

        assertThat(capturedAuditLogs).hasSize(1);
        AuditLogEntity auditLog = capturedAuditLogs.getFirst();
        assertThat(auditLog.getPerformedBy()).isNotNull();
        assertThat(auditLog.getPerformedBy()).isEqualTo(userName);
    }

    // --- Property: performedBy falls back to "SYSTEM" when unauthenticated ---

    @Property(tries = 100)
    void performedByFallsBackToSystemWhenUnauthenticated(
            @ForAll("testEntities") Object entity,
            @ForAll("operations") String operation
    ) {
        AdminService<Object, Object, Object, Long> service = createService();
        capturedAuditLogs.clear();

        // No authentication context set
        SecurityContextHolder.clearContext();

        service.saveAudit(null, entity, operation);

        assertThat(capturedAuditLogs).hasSize(1);
        AuditLogEntity auditLog = capturedAuditLogs.getFirst();
        assertThat(auditLog.getPerformedBy()).isNotNull();
        assertThat(auditLog.getPerformedBy()).isEqualTo("SYSTEM");
    }

    // --- Property: performedAt is within 1 second of operation time ---

    @Property(tries = 100)
    void performedAtWithinOneSecondTolerance(
            @ForAll("testEntities") Object entity,
            @ForAll("operations") String operation
    ) {
        AdminService<Object, Object, Object, Long> service = createService();
        capturedAuditLogs.clear();

        LocalDateTime before = LocalDateTime.now();
        service.saveAudit(null, entity, operation);
        LocalDateTime after = LocalDateTime.now();

        assertThat(capturedAuditLogs).hasSize(1);
        AuditLogEntity auditLog = capturedAuditLogs.getFirst();
        assertThat(auditLog.getPerformedAt()).isNotNull();

        // performedAt should be between before and after (within 1 second tolerance)
        assertThat(auditLog.getPerformedAt()).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(auditLog.getPerformedAt()).isBeforeOrEqualTo(after.plusSeconds(1));
    }

    // --- Property: snapshotAfter is serialized JSON when entity is passed as after ---

    @Property(tries = 100)
    void snapshotAfterContainsSerializedEntityForCreate(
            @ForAll("entityIds") Long entityId,
            @ForAll("operations") String operation
    ) {
        AdminService<Object, Object, Object, Long> service = createService();
        capturedAuditLogs.clear();

        TestEntity entity = new TestEntity(entityId);
        service.saveAudit(null, entity, operation);

        assertThat(capturedAuditLogs).hasSize(1);
        AuditLogEntity auditLog = capturedAuditLogs.getFirst();
        assertThat(auditLog.getSnapshotBefore()).isNull();
        assertThat(auditLog.getSnapshotAfter()).isNotNull();
        assertThat(auditLog.getSnapshotAfter()).contains("\"id\"");
    }

    // --- Property: snapshotBefore is serialized JSON when entity is passed as before ---

    @Property(tries = 100)
    void snapshotBeforeContainsSerializedEntityForDelete(
            @ForAll("entityIds") Long entityId
    ) {
        AdminService<Object, Object, Object, Long> service = createService();
        capturedAuditLogs.clear();

        TestEntity entity = new TestEntity(entityId);
        service.saveAudit(entity, null, "DELETE");

        assertThat(capturedAuditLogs).hasSize(1);
        AuditLogEntity auditLog = capturedAuditLogs.getFirst();
        assertThat(auditLog.getSnapshotBefore()).isNotNull();
        assertThat(auditLog.getSnapshotBefore()).contains("\"id\"");
        assertThat(auditLog.getSnapshotAfter()).isNull();
    }

    // --- Providers ---

    @Provide
    Arbitrary<Object> testEntities() {
        Arbitrary<Long> ids = Arbitraries.longs().between(1L, 100_000L);
        return ids.flatMap(id -> Arbitraries.of(
                new TestEntity(id),
                new OrderEntity(id),
                new ProjectEntity(id),
                new InvoiceEntity(id)
        ));
    }

    @Provide
    Arbitrary<Long> entityIds() {
        return Arbitraries.longs().between(1L, Long.MAX_VALUE);
    }

    @Provide
    Arbitrary<String> operations() {
        return Arbitraries.of("CREATE", "UPDATE", "DELETE", "SOFT_DELETE");
    }

    @Provide
    Arbitrary<String> userNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(20);
    }
}
