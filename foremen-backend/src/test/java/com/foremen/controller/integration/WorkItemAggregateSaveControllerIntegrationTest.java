package com.foremen.controller.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkRelatedWorkDao;
import com.foremen.dao.WorkVolumeFormulaDao;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;

/**
 * Integration tests for {@code PUT /api/work-items/{id}/aggregate} (FOR-04-11 bug-report Round 2,
 * item 1): all pending edits are applied in one transaction.
 *
 * <p>NOT {@code @Transactional}: the rollback assertion needs the request's own transaction to
 * commit or roll back. Repeatability: every run creates uniquely-named rows and removes them in
 * {@link #cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
class WorkItemAggregateSaveControllerIntegrationTest {

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
    private WorkItemDao workItemDao;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private RoomTypeDao roomTypeDao;
    @Autowired
    private WorkVolumeFormulaDao formulaDao;
    @Autowired
    private WorkRelatedWorkDao relatedWorkDao;
    @Autowired
    private TransactionTemplate tx;

    private WorkItemEntity item;
    private WorkItemEntity other;
    private RoomTypeEntity roomType;
    private WorkCategoryEntity category;
    private MeasurementUnitEntity unit;

    private String unique(String prefix) {
        return prefix + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private void createFixtures() {
        category = new WorkCategoryEntity();
        category.setCode(unique("WC"));
        category.setOrderNo(1);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        workCategoryDao.save(category);
        unit = new MeasurementUnitEntity();
        unit.setCode(unique("MU"));
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        measurementUnitDao.save(unit);
        item = new WorkItemEntity();
        item.setWorkCategory(category);
        item.setUnit(unit);
        item.setNameRU("Старое");
        item.setNamePL("Stare");
        item.setActive(true);
        item = workItemDao.save(item);
        other = new WorkItemEntity();
        other.setWorkCategory(category);
        other.setUnit(unit);
        other.setNameRU("Другая");
        other.setNamePL("Inna");
        other.setActive(true);
        other = workItemDao.save(other);
        roomType = new RoomTypeEntity();
        roomType.setCode(unique("RT"));
        roomType.setNameRU("Кухня");
        roomType.setNamePL("Kuchnia");
        roomType.setActive(true);
        roomType = roomTypeDao.save(roomType);
    }

    @AfterEach
    void cleanUp() {
        if (item == null) {
            return;
        }
        tx.executeWithoutResult(s -> {
            relatedWorkDao.findByWorkItemId(item.getId()).forEach(relatedWorkDao::delete);
            relatedWorkDao.findByWorkItemId(other.getId()).forEach(relatedWorkDao::delete);
            workItemDao.deleteById(other.getId());
            formulaDao.findByWorkItemId(item.getId()).ifPresent(formulaDao::delete);
            WorkItemEntity managed = workItemDao.findById(item.getId()).orElse(null);
            if (managed != null) {
                managed.getRoomTypes().clear();
                workItemDao.save(managed);
                workItemDao.delete(managed);
            }
            roomTypeDao.deleteById(roomType.getId());
            measurementUnitDao.deleteById(unit.getId());
            workCategoryDao.deleteById(category.getId());
        });
        item = null;
    }

    private String body(String formulaText) {
        return """
                {
                  "workItem": {"workCategoryId": %d, "unitId": %d, "nameRU": "Новое", "namePL": "Nowe", "active": true},
                  "formula": {"sourceText": "%s"},
                  "roomTypeIds": [%d]
                }
                """.formatted(category.getId(), unit.getId(), formulaText, roomType.getId());
    }

    @Test
    @DisplayName("applies attributes, formula and room types together")
    void appliesEverything() throws Exception {
        createFixtures();
        mockMvc.perform(put("/api/work-items/{id}/aggregate", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("floorArea*2")))
                .andExpect(status().isNoContent());

        tx.executeWithoutResult(s -> {
            WorkItemEntity saved = workItemDao.findById(item.getId()).orElseThrow();
            assertEquals("Nowe", saved.getNamePL());
            assertEquals(1, saved.getRoomTypes().size());
            assertTrue(formulaDao.findByWorkItemId(item.getId()).isPresent());
        });
    }

    @Test
    @DisplayName("an invalid formula rolls back the attribute and room-type changes")
    void rollsBackEverythingOnFailure() throws Exception {
        createFixtures();
        mockMvc.perform(put("/api/work-items/{id}/aggregate", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("floorArea*(")))
                .andExpect(status().is4xxClientError());

        tx.executeWithoutResult(s -> {
            WorkItemEntity saved = workItemDao.findById(item.getId()).orElseThrow();
            assertEquals("Stare", saved.getNamePL());
            assertTrue(saved.getRoomTypes().isEmpty());
            assertTrue(formulaDao.findByWorkItemId(item.getId()).isEmpty());
        });
    }

    @Test
    @DisplayName("applies the internal price and related works together (FOR-04-11)")
    void appliesInternalPriceAndRelatedWorks() throws Exception {
        createFixtures();
        mockMvc.perform(put("/api/work-items/{id}/aggregate", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workItem": {"workCategoryId": %d, "unitId": %d, "nameRU": "Новое", "namePL": "Nowe",
                                               "active": true, "internalPrice": 55.50},
                                  "relatedWorkIds": [%d]
                                }
                                """.formatted(category.getId(), unit.getId(), other.getId())))
                .andExpect(status().isNoContent());
        tx.executeWithoutResult(s -> {
            assertEquals(0, new java.math.BigDecimal("55.50").compareTo(
                    workItemDao.findById(item.getId()).orElseThrow().getInternalPrice()));
            assertEquals(java.util.List.of(other.getId()), relatedWorkDao.findRelatedIds(item.getId()));
        });
        mockMvc.perform(get("/api/work-items/{id}/related-works", item.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relatedWorkIds[0]").value(other.getId()));
    }

    @Test
    @DisplayName("a negative internal price is rejected and nothing is saved")
    void negativeInternalPriceRejected() throws Exception {
        createFixtures();
        mockMvc.perform(put("/api/work-items/{id}/aggregate", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workItem": {"workCategoryId": %d, "unitId": %d, "nameRU": "Новое", "namePL": "Nowe",
                                              "active": true, "internalPrice": -1}}
                                """.formatted(category.getId(), unit.getId())))
                .andExpect(status().isBadRequest());
        tx.executeWithoutResult(s -> assertNull(workItemDao.findById(item.getId()).orElseThrow().getInternalPrice()));
    }

    @Test
    @DisplayName("a self link rolls back the whole aggregate save")
    void selfRelatedLinkRollsBackEverything() throws Exception {
        createFixtures();
        mockMvc.perform(put("/api/work-items/{id}/aggregate", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workItem": {"workCategoryId": %d, "unitId": %d, "nameRU": "Новое", "namePL": "Nowe", "active": true},
                                  "relatedWorkIds": [%d]
                                }
                                """.formatted(category.getId(), unit.getId(), item.getId())))
                .andExpect(status().isBadRequest());
        tx.executeWithoutResult(s -> {
            assertEquals("Stare", workItemDao.findById(item.getId()).orElseThrow().getNamePL());
            assertTrue(relatedWorkDao.findRelatedIds(item.getId()).isEmpty());
        });
    }

    @Test
    @DisplayName("PUT related-works rejects a duplicate id with 400")
    void duplicateRelatedRejected() throws Exception {
        createFixtures();
        mockMvc.perform(put("/api/work-items/{id}/related-works", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"relatedWorkIds\": [%d, %d]}".formatted(other.getId(), other.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown work item returns 404")
    void unknownWorkItem() throws Exception {
        mockMvc.perform(put("/api/work-items/{id}/aggregate", 987_654_321L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomTypeIds\": []}"))
                .andExpect(status().isNotFound());
    }
}
