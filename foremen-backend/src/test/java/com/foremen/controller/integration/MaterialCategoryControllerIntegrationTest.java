package com.foremen.controller.integration;

import com.foremen.dao.MaterialCategoryDao;
import com.foremen.dao.model.MaterialCategoryEntity;
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
 * Integration tests for MaterialCategoryController CRUD operations.
 * <p>
 * Mirrors {@link DeliveryCategoryControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc
 * against a Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation.
 * <p>
 * Covers: create → list → read → update → delete, i18n {@code name} resolution
 * ({@code Accept-Language: ru} → nameRU, {@code pl}/absent → namePL), and {@code code}
 * immutability on update.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class MaterialCategoryControllerIntegrationTest {

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

    /** Per-run unique code suffix so scenarios are repeatable even without rollback. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MaterialCategoryDao materialCategoryDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String uniqueCode() {
        return "mc" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private MaterialCategoryEntity createTestMaterialCategory(String code, String nameRU, String namePL, boolean active) {
        MaterialCategoryEntity materialCategory = new MaterialCategoryEntity();
        materialCategory.setCode(code);
        materialCategory.setNameRU(nameRU);
        materialCategory.setNamePL(namePL);
        materialCategory.setActive(active);
        return materialCategoryDao.save(materialCategory);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/material-categories - creates a material category and echoes fields")
    void createMaterialCategory_returnsCreatedMaterialCategory() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/material-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Строительные",
                                    "namePL": "Construction",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Строительные"))
                .andExpect(jsonPath("$.namePL").value("Construction"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/material-categories - missing required fields returns 400")
    void createMaterialCategory_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/material-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "namePL": "tylko nazwa PL"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    // --- READ / LIST ---

    @Test
    @DisplayName("GET /api/material-categories - returns paginated material categories with name/code/active")
    void findMaterialCategories_returnsPaginatedResult() throws Exception {
        String code = uniqueCode();
        createTestMaterialCategory(code, "Отделочные", "Finishing", true);
        entityManager.flush();

        mockMvc.perform(get("/api/material-categories")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].active").value(contains(true)));
    }

    @Test
    @DisplayName("GET /api/material-categories/{id} - returns extended DTO (nameRU/namePL/code/active)")
    void findMaterialCategoryById_returnsExtendedModel() throws Exception {
        String code = uniqueCode();
        MaterialCategoryEntity materialCategory = createTestMaterialCategory(code, "Декор", "Dekor", true);
        entityManager.flush();

        mockMvc.perform(get("/api/material-categories/" + materialCategory.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(materialCategory.getId()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Декор"))
                .andExpect(jsonPath("$.namePL").value("Dekor"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/material-categories/{nonExistentId} - returns 404")
    void findMaterialCategoryById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/material-categories/99999"))
                .andExpect(status().isNotFound());
    }

    // --- i18n name resolution on the list endpoint ---

    @Test
    @DisplayName("GET /api/material-categories - Accept-Language: ru resolves name to nameRU")
    void listWithRussianLocale_resolvesNameToRU() throws Exception {
        String code = uniqueCode();
        createTestMaterialCategory(code, "Освещение", "Oświetlenie", true);
        entityManager.flush();

        mockMvc.perform(get("/api/material-categories")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Освещение")));
    }

    @Test
    @DisplayName("GET /api/material-categories - Accept-Language: pl resolves name to namePL")
    void listWithPolishLocale_resolvesNameToPL() throws Exception {
        String code = uniqueCode();
        createTestMaterialCategory(code, "Освещение", "Oświetlenie", true);
        entityManager.flush();

        mockMvc.perform(get("/api/material-categories")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Oświetlenie")));
    }

    @Test
    @DisplayName("GET /api/material-categories - absent Accept-Language falls back to namePL")
    void listWithoutLocale_fallsBackToPL() throws Exception {
        String code = uniqueCode();
        createTestMaterialCategory(code, "Двери", "Drzwi", true);
        entityManager.flush();

        mockMvc.perform(get("/api/material-categories")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Drzwi")));
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/material-categories/{id} - updates names/active; code is immutable")
    void updateMaterialCategory_updatesNamesKeepsCode() throws Exception {
        String code = uniqueCode();
        MaterialCategoryEntity materialCategory = createTestMaterialCategory(code, "старое имя", "stara nazwa", true);
        entityManager.flush();

        // Body has NO code field — names + active only.
        mockMvc.perform(put("/api/material-categories/" + materialCategory.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "новое имя",
                                    "namePL": "nowa nazwa",
                                    "active": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false))
                // code must remain unchanged after update (immutable).
                .andExpect(jsonPath("$.code").value(code));

        // Confirm via a fresh read that the code was not altered.
        mockMvc.perform(get("/api/material-categories/" + materialCategory.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/material-categories/{id} - deletes; subsequent read is 404")
    void deleteMaterialCategory_returns200ThenNotFound() throws Exception {
        String code = uniqueCode();
        MaterialCategoryEntity materialCategory = createTestMaterialCategory(code, "удаляемая", "usuwana", true);
        entityManager.flush();

        mockMvc.perform(delete("/api/material-categories/" + materialCategory.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/material-categories/" + materialCategory.getId()))
                .andExpect(status().isNotFound());
    }
}
