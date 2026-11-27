package com.foremen.controller.integration;

import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FOR-05-04 task 1.2: {@code aiInstructions} on the work category and work item create/update
 * endpoints: persisted and echoed, trimmed, blank stored as {@code null}, 8000-character limit,
 * and included in the audit snapshots.
 *
 * <p><b>Validates: Requirements 1.1, 1.2</b>
 *
 * <p>Repeatability: unique codes per run, {@code @Transactional} rollback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class WorkCatalogAiInstructionsIntegrationTest {

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

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private WorkItemDao workItemDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private AuditLogDao auditLogDao;
    @PersistenceContext
    private EntityManager entityManager;

    private static String unique(String prefix) {
        return prefix + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private WorkCategoryEntity category() {
        WorkCategoryEntity e = new WorkCategoryEntity();
        e.setCode(unique("WC"));
        e.setOrderNo(1);
        e.setNameRU("Категория");
        e.setNamePL("Kategoria");
        e.setActive(true);
        return workCategoryDao.save(e);
    }

    private MeasurementUnitEntity unit() {
        MeasurementUnitEntity e = new MeasurementUnitEntity();
        e.setCode(unique("MU"));
        e.setNameRU("Штука");
        e.setNamePL("Sztuka");
        e.setActive(true);
        return measurementUnitDao.save(e);
    }

    private static String json(String text) {
        return text == null ? "null" : "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private String categoryBody(String code, String ai) {
        return """
                {"code": "%s", "orderNo": 3, "nameRU": "Имя", "namePL": "Nazwa", "active": true, "aiInstructions": %s}
                """.formatted(code, json(ai));
    }

    private String itemBody(Long categoryId, Long unitId, String ai) {
        return """
                {"workCategoryId": %d, "unitId": %d, "nameRU": "Имя", "namePL": "Nazwa", "active": true,
                 "aiInstructions": %s}
                """.formatted(categoryId, unitId, json(ai));
    }

    // --- Work category ---

    @Test
    @DisplayName("category create: aiInstructions is trimmed, persisted and echoed")
    void categoryCreate_trimmedAndPersisted() throws Exception {
        String code = unique("WC");
        mockMvc.perform(post("/api/work-categories").contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody(code, "  Waterproofing: floor plus walls  \n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiInstructions").value("Waterproofing: floor plus walls"));
        entityManager.flush();
        entityManager.clear();
        assertThat(java.util.stream.StreamSupport.stream(workCategoryDao.findAll().spliterator(), false).filter(c -> code.equals(c.getCode())).findFirst()
                .orElseThrow().getAiInstructions()).isEqualTo("Waterproofing: floor plus walls");
    }

    @Test
    @DisplayName("category create/update: blank aiInstructions becomes null")
    void category_blankBecomesNull() throws Exception {
        String code = unique("WC");
        mockMvc.perform(post("/api/work-categories").contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody(code, "   \n\t ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiInstructions").value(nullValue()));

        WorkCategoryEntity existing = category();
        existing.setAiInstructions("old");
        workCategoryDao.save(existing);
        entityManager.flush();
        mockMvc.perform(put("/api/work-categories/" + existing.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody(existing.getCode(), "   ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiInstructions").value(nullValue()));
        entityManager.flush();
        entityManager.clear();
        assertThat(workCategoryDao.findById(existing.getId()).orElseThrow().getAiInstructions()).isNull();
    }

    @Test
    @DisplayName("category: 8000 chars accepted, 8001 rejected with 400")
    void category_lengthLimit() throws Exception {
        mockMvc.perform(post("/api/work-categories").contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody(unique("WC"), "a".repeat(8000))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/work-categories").contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody(unique("WC"), "a".repeat(8001))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("category update: aiInstructions changes and the audit snapshots carry it")
    void categoryUpdate_auditSnapshotIncludesField() throws Exception {
        WorkCategoryEntity existing = category();
        entityManager.flush();
        mockMvc.perform(put("/api/work-categories/" + existing.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody(existing.getCode(), "Section rule X")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiInstructions").value("Section rule X"));
        entityManager.flush();

        List<AuditLogEntity> logs = auditLogDao.findByEntityClassAndEntityIdOrderByPerformedAtAsc(
                "WorkCategoryEntity", existing.getId());
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(logs.size() - 1).getSnapshotAfter()).contains("aiInstructions", "Section rule X");
    }

    // --- Work item ---

    @Test
    @DisplayName("work item create: trimmed, persisted and echoed; audit CREATE snapshot carries it")
    void itemCreate_trimmedPersistedAudited() throws Exception {
        WorkCategoryEntity cat = category();
        MeasurementUnitEntity u = unit();
        entityManager.flush();
        String body = mockMvc.perform(post("/api/work-items").contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(cat.getId(), u.getId(), "  Work rule Y  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiInstructions").value("Work rule Y"))
                .andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        entityManager.flush();
        entityManager.clear();

        assertThat(workItemDao.findById(id).orElseThrow().getAiInstructions()).isEqualTo("Work rule Y");
        List<AuditLogEntity> logs = auditLogDao.findByEntityClassAndEntityIdOrderByPerformedAtAsc(
                "WorkItemEntity", id);
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0).getSnapshotAfter()).contains("aiInstructions", "Work rule Y");
    }

    @Test
    @DisplayName("work item update: blank clears the value, over-limit is rejected")
    void itemUpdate_blankAndLimit() throws Exception {
        WorkCategoryEntity cat = category();
        MeasurementUnitEntity u = unit();
        entityManager.flush();
        String created = mockMvc.perform(post("/api/work-items").contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(cat.getId(), u.getId(), "rule")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).get("id").asLong();
        entityManager.flush();

        mockMvc.perform(put("/api/work-items/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(cat.getId(), u.getId(), "a".repeat(8001))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/work-items/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(cat.getId(), u.getId(), "  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiInstructions").value(nullValue()));
        entityManager.flush();
        entityManager.clear();
        assertThat(workItemDao.findById(id).orElseThrow().getAiInstructions()).isNull();
    }
}
