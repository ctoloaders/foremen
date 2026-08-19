package com.foremen.service.integration;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.integration.dao.SampleEntityDao;
import com.foremen.service.integration.entity.SampleEntity;
import com.foremen.service.integration.entity.SampleServiceExtendedModel;
import com.foremen.service.integration.service.SampleAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for AdminService write operations (create, update, delete, softDelete,
 * setPropertiesToNull) and universal audit log verification.
 *
 * Validates Requirements: 12.1-12.3, 13.1-13.5, 14.1-14.7, 15.1-15.3, 16.1, 16.2
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class AdminServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private SampleAdminService sampleService;

    @Autowired
    private SampleEntityDao sampleDao;

    @Autowired
    private AuditLogDao auditLogDao;

    @PersistenceContext
    private EntityManager entityManager;

    private static final String TEST_USER = "test-admin@foremen.com";

    @BeforeEach
    void setUp() {
        auditLogDao.deleteAll();
        sampleDao.deleteAll();
        setSecurityContext(TEST_USER);
    }

    @AfterEach
    void tearDown() {
        auditLogDao.deleteAll();
        sampleDao.deleteAll();
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(String username) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(username, "password", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private SampleServiceExtendedModel createExtendedModel(String name, String code, String status, Integer age) {
        SampleServiceExtendedModel model = new SampleServiceExtendedModel();
        model.setName(name);
        model.setNameRU(name + " RU");
        model.setNamePL(name + " PL");
        model.setCode(code);
        model.setStatus(status);
        model.setAge(age);
        model.setPrice(BigDecimal.valueOf(100.00));
        model.setCity("Moscow");
        model.setCountry("Russia");
        return model;
    }

    // =====================================================================
    // CREATE OPERATIONS + AUDIT
    // =====================================================================

    @Nested
    @DisplayName("create operations")
    class CreateOperations {

        @Test
        @DisplayName("create single entity - ID generated, persisted, and audit_log has CREATE entry")
        void createSingleEntity_verifyIdAndAudit() {
            LocalDateTime before = LocalDateTime.now().minusSeconds(1);
            SampleServiceExtendedModel input = createExtendedModel("TestItem", "TST-001", "active", 25);

            SampleServiceExtendedModel result = sampleService.create(input);

            // Verify ID generated
            assertThat(result.getId()).isNotNull();

            // Verify entity persisted in DB
            assertThat(sampleDao.findById(result.getId())).isPresent();

            // Verify audit_log entry
            List<AuditLogEntity> auditLogs = auditLogDao.findAll();
            assertThat(auditLogs).hasSize(1);

            AuditLogEntity auditEntry = auditLogs.getFirst();
            assertThat(auditEntry.getEntityClass()).isEqualTo("SampleEntity");
            assertThat(auditEntry.getEntityId()).isEqualTo(result.getId());
            assertThat(auditEntry.getOperation()).isEqualTo("CREATE");
            assertThat(auditEntry.getPerformedBy()).isEqualTo(TEST_USER);
            assertThat(auditEntry.getPerformedAt()).isAfter(before);
            assertThat(auditEntry.getPerformedAt()).isBefore(LocalDateTime.now().plusSeconds(1));
        }

        @Test
        @DisplayName("create batch - all IDs generated, audit_log has N CREATE entries")
        void createBatch_verifyAllIdsAndAudit() {
            SampleServiceExtendedModel model1 = createExtendedModel("Batch1", "B-001", "active", 20);
            SampleServiceExtendedModel model2 = createExtendedModel("Batch2", "B-002", "active", 30);
            SampleServiceExtendedModel model3 = createExtendedModel("Batch3", "B-003", "active", 40);

            List<SampleServiceExtendedModel> results = sampleService.create(List.of(model1, model2, model3));

            // Verify all IDs generated
            assertThat(results).hasSize(3);
            assertThat(results).allSatisfy(r -> assertThat(r.getId()).isNotNull());

            // Verify all persisted
            assertThat(sampleDao.count()).isEqualTo(3);

            // Verify audit_log has 3 CREATE entries
            List<AuditLogEntity> auditLogs = auditLogDao.findAll();
            assertThat(auditLogs).hasSize(3);
            assertThat(auditLogs).allSatisfy(log -> {
                assertThat(log.getOperation()).isEqualTo("CREATE");
                assertThat(log.getEntityClass()).isEqualTo("SampleEntity");
                assertThat(log.getPerformedBy()).isEqualTo(TEST_USER);
            });
        }
    }

    // =====================================================================
    // UPDATE OPERATIONS + AUDIT
    // =====================================================================

    @Nested
    @DisplayName("update operations")
    class UpdateOperations {

        @Test
        @DisplayName("update existing entity - field changed in DB, audit_log has UPDATE entry")
        void updateExistingEntity_verifyChangeAndAudit() {
            // Create entity first
            SampleServiceExtendedModel created = sampleService.create(
                    createExtendedModel("Original", "O-001", "active", 25));
            auditLogDao.deleteAll(); // Clear CREATE audit to isolate UPDATE audit

            // Update
            SampleServiceExtendedModel updateModel = new SampleServiceExtendedModel();
            updateModel.setStatus("updated");
            updateModel.setAge(30);

            SampleServiceExtendedModel updated = sampleService.update(created.getId(), updateModel);

            // Verify field changed
            assertThat(updated.getStatus()).isEqualTo("updated");
            assertThat(updated.getAge()).isEqualTo(30);

            // Verify persisted in DB
            SampleEntity entity = sampleDao.findById(created.getId()).orElseThrow();
            assertThat(entity.getStatus()).isEqualTo("updated");
            assertThat(entity.getAge()).isEqualTo(30);

            // Verify audit_log has UPDATE entry
            List<AuditLogEntity> auditLogs = auditLogDao.findAll();
            assertThat(auditLogs).hasSize(1);
            AuditLogEntity auditEntry = auditLogs.getFirst();
            assertThat(auditEntry.getOperation()).isEqualTo("UPDATE");
            assertThat(auditEntry.getEntityId()).isEqualTo(created.getId());
            assertThat(auditEntry.getEntityClass()).isEqualTo("SampleEntity");
            assertThat(auditEntry.getPerformedBy()).isEqualTo(TEST_USER);
        }

        @Test
        @DisplayName("update non-existent ID - throws ForemenApiException 404")
        void updateNonExistentId_throws404() {
            SampleServiceExtendedModel updateModel = new SampleServiceExtendedModel();
            updateModel.setStatus("new-status");

            assertThatThrownBy(() -> sampleService.update(99999L, updateModel))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException apiEx = (ForemenApiException) ex;
                        assertThat(apiEx.getStatus().value()).isEqualTo(404);
                        assertThat(apiEx.getMessageCode()).isEqualTo("error.entity.not.found");
                    });
        }

        @Test
        @DisplayName("update preserves non-null fields when update model has nulls (null-ignore strategy)")
        void updatePreservesNonNullFields() {
            SampleServiceExtendedModel created = sampleService.create(
                    createExtendedModel("Preserved", "P-001", "active", 50));
            auditLogDao.deleteAll();

            // Update only status, leave other fields null
            SampleServiceExtendedModel updateModel = new SampleServiceExtendedModel();
            updateModel.setStatus("changed");

            SampleServiceExtendedModel updated = sampleService.update(created.getId(), updateModel);

            // Status changed
            assertThat(updated.getStatus()).isEqualTo("changed");
            // Other fields preserved (not nulled out due to null-ignore strategy)
            assertThat(updated.getCode()).isEqualTo("P-001");
            assertThat(updated.getAge()).isEqualTo(50);
        }
    }

    // =====================================================================
    // DELETE OPERATIONS + AUDIT
    // =====================================================================

    @Nested
    @DisplayName("delete operations")
    class DeleteOperations {

        @Test
        @DisplayName("deleteById - entity removed from DB, audit_log has DELETE entry")
        void deleteById_verifyRemovedAndAudit() {
            SampleServiceExtendedModel created = sampleService.create(
                    createExtendedModel("ToDelete", "D-001", "active", 33));
            Long entityId = created.getId();
            auditLogDao.deleteAll(); // Clear CREATE audit

            sampleService.deleteById(entityId);

            // Verify entity gone
            assertThat(sampleDao.findById(entityId)).isEmpty();

            // Verify audit_log has DELETE entry
            List<AuditLogEntity> auditLogs = auditLogDao.findAll();
            assertThat(auditLogs).hasSize(1);
            AuditLogEntity auditEntry = auditLogs.getFirst();
            assertThat(auditEntry.getOperation()).isEqualTo("DELETE");
            assertThat(auditEntry.getEntityId()).isEqualTo(entityId);
            assertThat(auditEntry.getEntityClass()).isEqualTo("SampleEntity");
            assertThat(auditEntry.getPerformedBy()).isEqualTo(TEST_USER);
        }

        @Test
        @DisplayName("deleteById non-existent ID - throws ForemenApiException 404")
        void deleteByIdNonExistentId_throws404() {
            assertThatThrownBy(() -> sampleService.deleteById(99999L))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException apiEx = (ForemenApiException) ex;
                        assertThat(apiEx.getStatus().value()).isEqualTo(404);
                        assertThat(apiEx.getMessageCode()).isEqualTo("error.entity.not.found");
                    });
        }
    }

    // =====================================================================
    // SOFT DELETE + AUDIT
    // =====================================================================

    @Nested
    @DisplayName("soft delete operations")
    class SoftDeleteOperations {

        @Test
        @DisplayName("softDelete - flag field set to true, entities still in DB, audit_log has SOFT_DELETE per entity")
        void softDelete_verifyFlagSetAndAudit() {
            SampleServiceExtendedModel created1 = sampleService.create(
                    createExtendedModel("SoftDel1", "SD-001", "active", 20));
            SampleServiceExtendedModel created2 = sampleService.create(
                    createExtendedModel("SoftDel2", "SD-002", "active", 30));
            auditLogDao.deleteAll(); // Clear CREATE audits

            sampleService.softDelete("deleted", Set.of(created1.getId(), created2.getId()));

            // CriteriaUpdate bypasses persistence context L1 cache — must clear before re-reading
            entityManager.clear();

            // Verify entities still in DB but flag set
            SampleEntity entity1 = sampleDao.findById(created1.getId()).orElseThrow();
            SampleEntity entity2 = sampleDao.findById(created2.getId()).orElseThrow();
            assertThat(entity1.getDeleted()).isTrue();
            assertThat(entity2.getDeleted()).isTrue();

            // Verify audit_log has SOFT_DELETE entries for each entity
            List<AuditLogEntity> auditLogs = auditLogDao.findAll();
            assertThat(auditLogs).hasSize(2);
            assertThat(auditLogs).allSatisfy(log -> {
                assertThat(log.getOperation()).isEqualTo("SOFT_DELETE");
                assertThat(log.getEntityClass()).isEqualTo("SampleEntity");
                assertThat(log.getPerformedBy()).isEqualTo(TEST_USER);
            });
            assertThat(auditLogs).extracting(AuditLogEntity::getEntityId)
                    .containsExactlyInAnyOrder(created1.getId(), created2.getId());
        }
    }

    // =====================================================================
    // SET PROPERTIES TO NULL
    // =====================================================================

    @Nested
    @DisplayName("setPropertiesToNull operations")
    class SetPropertiesToNullOperations {

        @Test
        @DisplayName("setPropertiesToNull - persist entity, null specific fields, verify via findById")
        void setPropertiesToNull_verifyFieldsNulled() {
            SampleServiceExtendedModel created = sampleService.create(
                    createExtendedModel("NullFields", "NF-001", "active", 45));
            Long entityId = created.getId();

            // Verify fields are non-null before
            SampleEntity before = sampleDao.findById(entityId).orElseThrow();
            assertThat(before.getCity()).isNotNull();
            assertThat(before.getCountry()).isNotNull();

            sampleService.setPropertiesToNull(entityId, Set.of("city", "country"));

            // CriteriaUpdate bypasses the persistence context L1 cache
            // Must clear to force fresh read from database
            entityManager.clear();

            // Query directly from DB to verify fields are null
            SampleEntity after = sampleDao.findById(entityId).orElseThrow();
            assertThat(after.getCity()).isNull();
            assertThat(after.getCountry()).isNull();
            // Other fields should be preserved
            assertThat(after.getCode()).isEqualTo("NF-001");
            assertThat(after.getAge()).isEqualTo(45);
        }

        @Test
        @DisplayName("setPropertiesToNull with invalid field - throws ForemenApiException 400")
        void setPropertiesToNullInvalidField_throws400() {
            SampleServiceExtendedModel created = sampleService.create(
                    createExtendedModel("InvalidField", "IF-001", "active", 50));

            assertThatThrownBy(() -> sampleService.setPropertiesToNull(
                    created.getId(), Set.of("nonExistentField")))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException apiEx = (ForemenApiException) ex;
                        assertThat(apiEx.getStatus().value()).isEqualTo(400);
                        assertThat(apiEx.getMessageCode()).isEqualTo("error.invalid.field.name");
                    });
        }
    }

    // =====================================================================
    // COMPREHENSIVE AUDIT LOG VERIFICATION
    // =====================================================================

    @Nested
    @DisplayName("comprehensive audit log verification")
    class ComprehensiveAuditVerification {

        @Test
        @DisplayName("after multiple CRUD operations, all operations recorded in audit_log with correct data")
        void multipleCrudOperations_allAuditRecordsCorrect() {
            LocalDateTime testStart = LocalDateTime.now().minusSeconds(1);

            // 1. CREATE
            SampleServiceExtendedModel created = sampleService.create(
                    createExtendedModel("AuditTest", "AT-001", "active", 25));
            Long entityId = created.getId();

            // 2. UPDATE
            SampleServiceExtendedModel updateModel = new SampleServiceExtendedModel();
            updateModel.setStatus("updated");
            sampleService.update(entityId, updateModel);

            // 3. CREATE another entity for batch operations
            SampleServiceExtendedModel created2 = sampleService.create(
                    createExtendedModel("AuditTest2", "AT-002", "active", 35));

            // 4. SOFT_DELETE
            sampleService.softDelete("deleted", Set.of(created2.getId()));

            // 5. DELETE the first entity
            sampleService.deleteById(entityId);

            // Query audit_log table directly — verify ALL operations recorded
            List<AuditLogEntity> allAuditLogs = auditLogDao.findAll();

            // Should have 5 entries: CREATE + UPDATE + CREATE + SOFT_DELETE + DELETE
            assertThat(allAuditLogs).hasSize(5);

            // Verify CREATE entries
            List<AuditLogEntity> createLogs = allAuditLogs.stream()
                    .filter(log -> "CREATE".equals(log.getOperation()))
                    .toList();
            assertThat(createLogs).hasSize(2);

            // Verify UPDATE entry
            List<AuditLogEntity> updateLogs = allAuditLogs.stream()
                    .filter(log -> "UPDATE".equals(log.getOperation()))
                    .toList();
            assertThat(updateLogs).hasSize(1);
            assertThat(updateLogs.getFirst().getEntityId()).isEqualTo(entityId);

            // Verify SOFT_DELETE entry
            List<AuditLogEntity> softDeleteLogs = allAuditLogs.stream()
                    .filter(log -> "SOFT_DELETE".equals(log.getOperation()))
                    .toList();
            assertThat(softDeleteLogs).hasSize(1);
            assertThat(softDeleteLogs.getFirst().getEntityId()).isEqualTo(created2.getId());

            // Verify DELETE entry
            List<AuditLogEntity> deleteLogs = allAuditLogs.stream()
                    .filter(log -> "DELETE".equals(log.getOperation()))
                    .toList();
            assertThat(deleteLogs).hasSize(1);
            assertThat(deleteLogs.getFirst().getEntityId()).isEqualTo(entityId);

            // Verify all entries have correct common fields
            assertThat(allAuditLogs).allSatisfy(log -> {
                assertThat(log.getEntityClass()).isEqualTo("SampleEntity");
                assertThat(log.getPerformedBy()).isEqualTo(TEST_USER);
                assertThat(log.getPerformedAt()).isAfter(testStart);
                assertThat(log.getPerformedAt()).isBefore(LocalDateTime.now().plusSeconds(1));
                assertThat(log.getEntityId()).isNotNull();
            });
        }

        @Test
        @DisplayName("audit records performedBy as SYSTEM when no security context")
        void auditRecordsSystemWhenNoSecurityContext() {
            SecurityContextHolder.clearContext();

            SampleServiceExtendedModel created = sampleService.create(
                    createExtendedModel("NoAuth", "NA-001", "active", 20));

            List<AuditLogEntity> auditLogs = auditLogDao.findAll();
            assertThat(auditLogs).hasSize(1);
            assertThat(auditLogs.getFirst().getPerformedBy()).isEqualTo("SYSTEM");
        }

        @Test
        @DisplayName("audit records different users correctly")
        void auditRecordsDifferentUsersCorrectly() {
            // First operation by user A
            setSecurityContext("userA@foremen.com");
            SampleServiceExtendedModel created = sampleService.create(
                    createExtendedModel("UserA", "UA-001", "active", 25));

            // Second operation by user B
            setSecurityContext("userB@foremen.com");
            SampleServiceExtendedModel updateModel = new SampleServiceExtendedModel();
            updateModel.setStatus("changed");
            sampleService.update(created.getId(), updateModel);

            List<AuditLogEntity> auditLogs = auditLogDao.findAll();
            assertThat(auditLogs).hasSize(2);

            AuditLogEntity createLog = auditLogs.stream()
                    .filter(l -> "CREATE".equals(l.getOperation()))
                    .findFirst().orElseThrow();
            AuditLogEntity updateLog = auditLogs.stream()
                    .filter(l -> "UPDATE".equals(l.getOperation()))
                    .findFirst().orElseThrow();

            assertThat(createLog.getPerformedBy()).isEqualTo("userA@foremen.com");
            assertThat(updateLog.getPerformedBy()).isEqualTo("userB@foremen.com");
        }
    }
}
