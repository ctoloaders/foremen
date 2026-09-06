package com.foremen.controller.integration;

import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link com.foremen.controller.WorkItemController} CRUD operations.
 *
 * <p>Mirrors {@link WorkCategoryControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc against a
 * Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation. Since {@code WorkItem} carries two FKs, each scenario
 * first persists a {@link WorkCategoryEntity} and a {@link MeasurementUnitEntity} fixture and uses their
 * generated ids as {@code workCategoryId}/{@code unitId}.
 *
 * <p>Covers: create with valid FK ids → list (rows expose FK ids AND referenced localized names) →
 * extended read → update (change FKs + names) → delete → 404, i18n RU/PL/fallback on {@code name},
 * and filtering by {@code workCategory.id} via the reference-filter grammar.
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.5, 2.6, 5.1
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class WorkItemControllerIntegrationTest {

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

    /** Per-run unique code suffix so FK fixtures never collide. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkItemDao workItemDao;

    @Autowired
    private WorkCategoryDao workCategoryDao;

    @Autowired
    private MeasurementUnitDao measurementUnitDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String unique(String prefix) {
        return prefix + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private WorkCategoryEntity createCategory(String nameRU, String namePL) {
        WorkCategoryEntity entity = new WorkCategoryEntity();
        entity.setCode(unique("WC"));
        entity.setOrderNo(1);
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);
        entity.setActive(true);
        return workCategoryDao.save(entity);
    }

    private MeasurementUnitEntity createUnit(String nameRU, String namePL) {
        MeasurementUnitEntity entity = new MeasurementUnitEntity();
        entity.setCode(unique("MU"));
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);
        entity.setActive(true);
        return measurementUnitDao.save(entity);
    }

    private WorkItemEntity createWorkItem(WorkCategoryEntity category, MeasurementUnitEntity unit,
                                          String nameRU, String namePL, boolean active) {
        WorkItemEntity entity = new WorkItemEntity();
        entity.setWorkCategory(category);
        entity.setUnit(unit);
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);
        entity.setActive(active);
        return workItemDao.save(entity);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/work-items - creates an item with valid FK ids and echoes fields")
    void createWorkItem_returnsCreatedItem() throws Exception {
        WorkCategoryEntity category = createCategory("Плиточные работы", "PRACE GLAZURNICZE");
        MeasurementUnitEntity unit = createUnit("Квадратный метр", "Metr kwadratowy");
        entityManager.flush();

        mockMvc.perform(post("/api/work-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "workCategoryId": %d,
                                    "unitId": %d,
                                    "nameRU": "Укладка плитки",
                                    "namePL": "Układanie płytek",
                                    "active": true
                                }
                                """.formatted(category.getId(), unit.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.workCategoryId").value(category.getId()))
                .andExpect(jsonPath("$.unitId").value(unit.getId()))
                .andExpect(jsonPath("$.nameRU").value("Укладка плитки"))
                .andExpect(jsonPath("$.namePL").value("Układanie płytek"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/work-items - missing workCategoryId returns 400")
    void createWorkItem_missingCategory_returns400() throws Exception {
        MeasurementUnitEntity unit = createUnit("Штука", "Sztuka");
        entityManager.flush();

        mockMvc.perform(post("/api/work-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "unitId": %d,
                                    "nameRU": "имя",
                                    "namePL": "nazwa"
                                }
                                """.formatted(unit.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/work-items - missing unitId returns 400")
    void createWorkItem_missingUnit_returns400() throws Exception {
        WorkCategoryEntity category = createCategory("Полы", "POSADZKI");
        entityManager.flush();

        mockMvc.perform(post("/api/work-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "workCategoryId": %d,
                                    "nameRU": "имя",
                                    "namePL": "nazwa"
                                }
                                """.formatted(category.getId())))
                .andExpect(status().isBadRequest());
    }

    // --- READ / LIST ---

    @Test
    @DisplayName("GET /api/work-items - rows expose FK ids AND referenced localized names")
    void findWorkItems_returnsFkIdsAndReferencedNames() throws Exception {
        WorkCategoryEntity category = createCategory("Плиточные работы", "PRACE GLAZURNICZE");
        MeasurementUnitEntity unit = createUnit("Квадратный метр", "Metr kwadratowy");
        WorkItemEntity item = createWorkItem(category, unit, "Укладка плитки", "Układanie płytek", true);
        entityManager.flush();

        mockMvc.perform(get("/api/work-items")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.id == " + item.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + item.getId() + ")].workCategoryId")
                        .value(contains(category.getId().intValue())))
                .andExpect(jsonPath("$.content[?(@.id == " + item.getId() + ")].unitId")
                        .value(contains(unit.getId().intValue())))
                .andExpect(jsonPath("$.content[?(@.id == " + item.getId() + ")].workCategoryName")
                        .value(contains("PRACE GLAZURNICZE")))
                .andExpect(jsonPath("$.content[?(@.id == " + item.getId() + ")].unitName")
                        .value(contains("Metr kwadratowy")))
                .andExpect(jsonPath("$.content[?(@.id == " + item.getId() + ")].name")
                        .value(contains("Układanie płytek")));
    }

    @Test
    @DisplayName("GET /api/work-items - Accept-Language: ru resolves referenced names to RU")
    void findWorkItems_russianLocale_resolvesReferencedNamesToRU() throws Exception {
        WorkCategoryEntity category = createCategory("Плиточные работы", "PRACE GLAZURNICZE");
        MeasurementUnitEntity unit = createUnit("Квадратный метр", "Metr kwadratowy");
        WorkItemEntity item = createWorkItem(category, unit, "Укладка плитки", "Układanie płytek", true);
        entityManager.flush();

        mockMvc.perform(get("/api/work-items")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + item.getId() + ")].name")
                        .value(contains("Укладка плитки")))
                .andExpect(jsonPath("$.content[?(@.id == " + item.getId() + ")].workCategoryName")
                        .value(contains("Плиточные работы")))
                .andExpect(jsonPath("$.content[?(@.id == " + item.getId() + ")].unitName")
                        .value(contains("Квадратный метр")));
    }

    @Test
    @DisplayName("GET /api/work-items - absent Accept-Language falls back to PL for name")
    void findWorkItems_noLocale_fallsBackToPL() throws Exception {
        WorkCategoryEntity category = createCategory("Полы", "POSADZKI");
        MeasurementUnitEntity unit = createUnit("Штука", "Sztuka");
        WorkItemEntity item = createWorkItem(category, unit, "Работа", "Praca", true);
        entityManager.flush();

        mockMvc.perform(get("/api/work-items")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + item.getId() + ")].name")
                        .value(contains("Praca")));
    }

    @Test
    @DisplayName("GET /api/work-items/{id} - returns extended DTO with FK ids + RU/PL names")
    void findWorkItemById_returnsExtendedModel() throws Exception {
        WorkCategoryEntity category = createCategory("Столярка", "STOLARKA");
        MeasurementUnitEntity unit = createUnit("Погонный метр", "Metr bieżący");
        WorkItemEntity item = createWorkItem(category, unit, "Монтаж плинтуса", "Montaż listwy", true);
        entityManager.flush();

        mockMvc.perform(get("/api/work-items/" + item.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(item.getId()))
                .andExpect(jsonPath("$.workCategoryId").value(category.getId()))
                .andExpect(jsonPath("$.unitId").value(unit.getId()))
                .andExpect(jsonPath("$.nameRU").value("Монтаж плинтуса"))
                .andExpect(jsonPath("$.namePL").value("Montaż listwy"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/work-items/{nonExistentId} - returns 404")
    void findWorkItemById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/work-items/99999"))
                .andExpect(status().isNotFound());
    }

    // --- FILTER by reference (workCategory.id) ---

    @Test
    @DisplayName("GET /api/work-items?query=workCategory.id==<id> - filters by referenced category")
    void findWorkItems_filterByWorkCategoryId() throws Exception {
        WorkCategoryEntity categoryA = createCategory("Категория A", "Kategoria A");
        WorkCategoryEntity categoryB = createCategory("Категория B", "Kategoria B");
        MeasurementUnitEntity unit = createUnit("Штука", "Sztuka");
        WorkItemEntity itemA = createWorkItem(categoryA, unit, "Работа A", "Praca A", true);
        WorkItemEntity itemB = createWorkItem(categoryB, unit, "Работа B", "Praca B", true);
        entityManager.flush();

        mockMvc.perform(get("/api/work-items")
                        .param("query", "workCategory.id==" + categoryA.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + itemA.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + itemB.getId() + ")]").doesNotExist());
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/work-items/{id} - updates FKs and names")
    void updateWorkItem_changesFksAndNames() throws Exception {
        WorkCategoryEntity categoryOld = createCategory("Старая категория", "Stara kategoria");
        WorkCategoryEntity categoryNew = createCategory("Новая категория", "Nowa kategoria");
        MeasurementUnitEntity unitOld = createUnit("Штука", "Sztuka");
        MeasurementUnitEntity unitNew = createUnit("Метр", "Metr");
        WorkItemEntity item = createWorkItem(categoryOld, unitOld, "старое имя", "stara nazwa", true);
        entityManager.flush();

        mockMvc.perform(put("/api/work-items/" + item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "workCategoryId": %d,
                                    "unitId": %d,
                                    "nameRU": "новое имя",
                                    "namePL": "nowa nazwa",
                                    "active": false
                                }
                                """.formatted(categoryNew.getId(), unitNew.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workCategoryId").value(categoryNew.getId()))
                .andExpect(jsonPath("$.unitId").value(unitNew.getId()))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/work-items/" + item.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workCategoryId").value(categoryNew.getId()))
                .andExpect(jsonPath("$.unitId").value(unitNew.getId()))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/work-items/{id} - deletes; subsequent read is 404")
    void deleteWorkItem_returns200ThenNotFound() throws Exception {
        WorkCategoryEntity category = createCategory("Прочее", "INNE");
        MeasurementUnitEntity unit = createUnit("Штука", "Sztuka");
        WorkItemEntity item = createWorkItem(category, unit, "удаляемая", "usuwana", true);
        entityManager.flush();

        mockMvc.perform(delete("/api/work-items/" + item.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/work-items/" + item.getId()))
                .andExpect(status().isNotFound());
    }
}
