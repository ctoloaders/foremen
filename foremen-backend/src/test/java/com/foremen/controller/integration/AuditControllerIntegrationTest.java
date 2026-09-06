package com.foremen.controller.integration;

import com.foremen.dao.AuditReadOnlyDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AuditController (read-only endpoint at /api/audit).
 * <p>
 * Validates: Requirements 2.1, 2.2, 2.3, 2.6
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class AuditControllerIntegrationTest {

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
    private MockMvc mockMvc;

    @Autowired
    private AuditReadOnlyDao auditReadOnlyDao;

    @PersistenceContext
    private EntityManager entityManager;

    private AuditLogEntity audit1;
    private AuditLogEntity audit2;
    private AuditLogEntity audit3;

    @BeforeEach
    void seedAuditData() {
        audit1 = createAuditEntry("RoleEntity", 1L, "CREATE", "admin@foremen.com",
                LocalDateTime.of(2024, 1, 15, 10, 0, 0),
                null, "{\"code\":\"ADMIN\",\"nameRU\":\"Администратор\"}");

        audit2 = createAuditEntry("RoleEntity", 1L, "UPDATE", "admin@foremen.com",
                LocalDateTime.of(2024, 1, 16, 12, 30, 0),
                "{\"code\":\"ADMIN\",\"nameRU\":\"Администратор\"}",
                "{\"code\":\"ADMIN\",\"nameRU\":\"Супер Администратор\"}");

        audit3 = createAuditEntry("ResourceEntity", 5L, "CREATE", "system",
                LocalDateTime.of(2024, 1, 17, 8, 0, 0),
                null, "{\"code\":\"USERS\",\"nameRU\":\"Пользователи\"}");

        entityManager.flush();
    }

    private AuditLogEntity createAuditEntry(String entityClass, Long entityId, String operation,
                                             String performedBy, LocalDateTime performedAt,
                                             String snapshotBefore, String snapshotAfter) {
        AuditLogEntity entry = new AuditLogEntity();
        entry.setEntityClass(entityClass);
        entry.setEntityId(entityId);
        entry.setOperation(operation);
        entry.setPerformedBy(performedBy);
        entry.setPerformedAt(performedAt);
        entry.setSnapshotBefore(snapshotBefore);
        entry.setSnapshotAfter(snapshotAfter);
        entityManager.persist(entry);
        return entry;
    }

    // --- GET /api/audit (paginated list) ---

    @Test
    @DisplayName("GET /api/audit → 200 with paginated response containing seeded records")
    void find_returnsPaginatedAuditRecords() throws Exception {
        mockMvc.perform(get("/api/audit")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].entityClass").isString())
                .andExpect(jsonPath("$.content[0].operation").isString())
                .andExpect(jsonPath("$.content[0].performedBy").isString())
                .andExpect(jsonPath("$.content[0].performedAt").isString());
    }

    // --- GET /api/audit?query=entityClass==RoleEntity (filtered) ---

    @Test
    @DisplayName("GET /api/audit?query=entityClass==RoleEntity → filtered results")
    void find_withEntityClassFilter_returnsFilteredResults() throws Exception {
        mockMvc.perform(get("/api/audit")
                        .param("query", "entityClass==RoleEntity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].entityClass").value("RoleEntity"))
                .andExpect(jsonPath("$.content[1].entityClass").value("RoleEntity"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    // --- GET /api/audit?sort=performedAt,desc (sorted) ---

    @Test
    @DisplayName("GET /api/audit?sort=performedAt,desc → sorted results (most recent first)")
    void find_withSortDesc_returnsSortedResults() throws Exception {
        mockMvc.perform(get("/api/audit")
                        .param("sort", "performedAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].entityClass").value("ResourceEntity"))
                .andExpect(jsonPath("$.content[1].entityClass").value("RoleEntity"))
                .andExpect(jsonPath("$.content[1].operation").value("UPDATE"))
                .andExpect(jsonPath("$.content[2].entityClass").value("RoleEntity"))
                .andExpect(jsonPath("$.content[2].operation").value("CREATE"));
    }

    // --- GET /api/audit/metadata ---

    @Test
    @DisplayName("GET /api/audit/metadata → 200 with field list and Cache-Control header")
    void getMetadata_returnsFieldListWithCacheControl() throws Exception {
        mockMvc.perform(get("/api/audit/metadata"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("max-age=86400")))
                .andExpect(jsonPath("$.fields").isArray())
                .andExpect(jsonPath("$.fields.length()").value(greaterThanOrEqualTo(6)))
                .andExpect(jsonPath("$.fields[?(@.name == 'entityClass')]").exists())
                .andExpect(jsonPath("$.fields[?(@.name == 'entityId')]").exists())
                .andExpect(jsonPath("$.fields[?(@.name == 'operation')]").exists())
                .andExpect(jsonPath("$.fields[?(@.name == 'performedBy')]").exists())
                .andExpect(jsonPath("$.fields[?(@.name == 'performedAt')]").exists());
    }

    // --- POST /api/audit → 405 Method Not Allowed ---

    @Test
    @DisplayName("POST /api/audit → 405 Method Not Allowed (read-only)")
    void post_returnsMethodNotAllowed() throws Exception {
        mockMvc.perform(post("/api/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entityClass\":\"TestEntity\",\"operation\":\"CREATE\"}"))
                .andExpect(status().isMethodNotAllowed());
    }

    // --- DELETE /api/audit/1 → 405 Method Not Allowed ---

    @Test
    @DisplayName("DELETE /api/audit/1 → 405 Method Not Allowed (read-only)")
    void delete_returnsMethodNotAllowed() throws Exception {
        mockMvc.perform(delete("/api/audit/" + audit1.getId()))
                .andExpect(status().isMethodNotAllowed());
    }
}
