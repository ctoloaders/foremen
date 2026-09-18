package com.foremen.controller.integration;

import com.foremen.dao.MaterialDao;
import com.foremen.dao.model.MaterialEntity;
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
 * Integration tests for MaterialController CRUD operations.
 * <p>
 * Mirrors {@link MaterialCategoryControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc
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
class MaterialControllerIntegrationTest {

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
    private MaterialDao materialDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String uniqueCode() {
        return "mat" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private MaterialEntity createTestMaterial(String code, String nameRU, String namePL, boolean active) {
        MaterialEntity material = new MaterialEntity();
        material.setCode(code);
        material.setNameRU(nameRU);
        material.setNamePL(namePL);
        material.setActive(active);
        return materialDao.save(material);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/materials - creates a material and echoes fields")
    void createMaterial_returnsCreatedMaterial() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Напольное покрытие",
                                    "namePL": "Podłoga",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Напольное покрытие"))
                .andExpect(jsonPath("$.namePL").value("Podłoga"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/materials - missing required fields returns 400")
    void createMaterial_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/materials")
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
    @DisplayName("GET /api/materials - returns paginated materials with name/code/active")
    void findMaterials_returnsPaginatedResult() throws Exception {
        String code = uniqueCode();
        createTestMaterial(code, "Плитка", "Płytki", true);
        entityManager.flush();

        mockMvc.perform(get("/api/materials")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].active").value(contains(true)));
    }

    @Test
    @DisplayName("GET /api/materials/{id} - returns extended DTO (nameRU/namePL/code/active)")
    void findMaterialById_returnsExtendedModel() throws Exception {
        String code = uniqueCode();
        MaterialEntity material = createTestMaterial(code, "Ламинат", "Laminat", true);
        entityManager.flush();

        mockMvc.perform(get("/api/materials/" + material.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(material.getId()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Ламинат"))
                .andExpect(jsonPath("$.namePL").value("Laminat"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/materials/{nonExistentId} - returns 404")
    void findMaterialById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/materials/99999"))
                .andExpect(status().isNotFound());
    }

    // --- i18n name resolution on the list endpoint ---

    @Test
    @DisplayName("GET /api/materials - Accept-Language: ru resolves name to nameRU")
    void listWithRussianLocale_resolvesNameToRU() throws Exception {
        String code = uniqueCode();
        createTestMaterial(code, "Сантехника", "Sanitariat", true);
        entityManager.flush();

        mockMvc.perform(get("/api/materials")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Сантехника")));
    }

    @Test
    @DisplayName("GET /api/materials - Accept-Language: pl resolves name to namePL")
    void listWithPolishLocale_resolvesNameToPL() throws Exception {
        String code = uniqueCode();
        createTestMaterial(code, "Сантехника", "Sanitariat", true);
        entityManager.flush();

        mockMvc.perform(get("/api/materials")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Sanitariat")));
    }

    @Test
    @DisplayName("GET /api/materials - absent Accept-Language falls back to namePL")
    void listWithoutLocale_fallsBackToPL() throws Exception {
        String code = uniqueCode();
        createTestMaterial(code, "Двери", "Drzwi", true);
        entityManager.flush();

        mockMvc.perform(get("/api/materials")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Drzwi")));
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/materials/{id} - updates names/active; code is immutable")
    void updateMaterial_updatesNamesKeepsCode() throws Exception {
        String code = uniqueCode();
        MaterialEntity material = createTestMaterial(code, "старое имя", "stara nazwa", true);
        entityManager.flush();

        // Body has NO code field — names + active only.
        mockMvc.perform(put("/api/materials/" + material.getId())
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
        mockMvc.perform(get("/api/materials/" + material.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/materials/{id} - deletes; subsequent read is 404")
    void deleteMaterial_returns200ThenNotFound() throws Exception {
        String code = uniqueCode();
        MaterialEntity material = createTestMaterial(code, "удаляемая", "usuwana", true);
        entityManager.flush();

        mockMvc.perform(delete("/api/materials/" + material.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/materials/" + material.getId()))
                .andExpect(status().isNotFound());
    }
}
