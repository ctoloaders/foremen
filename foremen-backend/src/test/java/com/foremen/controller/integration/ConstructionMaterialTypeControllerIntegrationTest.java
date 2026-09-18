package com.foremen.controller.integration;

import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
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
 * Integration tests for ConstructionMaterialTypeController CRUD operations.
 * <p>
 * Mirrors {@link MaterialTypeControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc
 * against a Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation.
 * <p>
 * Covers: create → list → read → update → delete, i18n {@code name} resolution
 * ({@code Accept-Language: ru} → nameRU, {@code pl}/absent → namePL), {@code code}
 * immutability on update, and create validation ({@code 400} on blank code/nameRU/namePL).
 * <p>
 * Validates Requirements 12.1, 1.5, 1.6, 1.7.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class ConstructionMaterialTypeControllerIntegrationTest {

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
    private ConstructionMaterialTypeDao constructionMaterialTypeDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String uniqueCode() {
        return "cmt" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private ConstructionMaterialTypeEntity createTestType(String code, String nameRU, String namePL, boolean active) {
        ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
        type.setCode(code);
        type.setNameRU(nameRU);
        type.setNamePL(namePL);
        type.setActive(active);
        return constructionMaterialTypeDao.save(type);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/construction-material-types - creates a type and echoes fields")
    void createType_returnsCreatedType() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/construction-material-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Краска",
                                    "namePL": "Farba",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Краска"))
                .andExpect(jsonPath("$.namePL").value("Farba"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/construction-material-types - missing required fields returns 400")
    void createType_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/construction-material-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "namePL": "tylko nazwa PL"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/construction-material-types - blank code returns 400")
    void createType_blankCode_returns400() throws Exception {
        mockMvc.perform(post("/api/construction-material-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "",
                                    "nameRU": "Краска",
                                    "namePL": "Farba",
                                    "active": true
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/construction-material-types - blank nameRU returns 400")
    void createType_blankNameRU_returns400() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/construction-material-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "",
                                    "namePL": "Farba",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/construction-material-types - blank namePL returns 400")
    void createType_blankNamePL_returns400() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/construction-material-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Краска",
                                    "namePL": "",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isBadRequest());
    }

    // --- READ / LIST ---

    @Test
    @DisplayName("GET /api/construction-material-types - returns paginated types with name/code/active")
    void findTypes_returnsPaginatedResult() throws Exception {
        String code = uniqueCode();
        createTestType(code, "Клей для плитки", "Klej do płytek", true);
        entityManager.flush();

        mockMvc.perform(get("/api/construction-material-types")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].active").value(contains(true)));
    }

    @Test
    @DisplayName("GET /api/construction-material-types/{id} - returns extended DTO (nameRU/namePL/code/active)")
    void findTypeById_returnsExtendedModel() throws Exception {
        String code = uniqueCode();
        ConstructionMaterialTypeEntity type = createTestType(code, "Грунтовка", "Grunt", true);
        entityManager.flush();

        mockMvc.perform(get("/api/construction-material-types/" + type.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(type.getId()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Грунтовка"))
                .andExpect(jsonPath("$.namePL").value("Grunt"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/construction-material-types/{nonExistentId} - returns 404")
    void findTypeById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/construction-material-types/99999"))
                .andExpect(status().isNotFound());
    }

    // --- i18n name resolution on the list endpoint ---

    @Test
    @DisplayName("GET /api/construction-material-types - Accept-Language: ru resolves name to nameRU")
    void listWithRussianLocale_resolvesNameToRU() throws Exception {
        String code = uniqueCode();
        createTestType(code, "Шпаклёвка", "Szpachla", true);
        entityManager.flush();

        mockMvc.perform(get("/api/construction-material-types")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Шпаклёвка")));
    }

    @Test
    @DisplayName("GET /api/construction-material-types - Accept-Language: pl resolves name to namePL")
    void listWithPolishLocale_resolvesNameToPL() throws Exception {
        String code = uniqueCode();
        createTestType(code, "Шпаклёвка", "Szpachla", true);
        entityManager.flush();

        mockMvc.perform(get("/api/construction-material-types")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Szpachla")));
    }

    @Test
    @DisplayName("GET /api/construction-material-types - absent Accept-Language falls back to namePL")
    void listWithoutLocale_fallsBackToPL() throws Exception {
        String code = uniqueCode();
        createTestType(code, "Краска", "Farba", true);
        entityManager.flush();

        mockMvc.perform(get("/api/construction-material-types")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Farba")));
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/construction-material-types/{id} - updates names/active; code is immutable")
    void updateType_updatesNamesKeepsCode() throws Exception {
        String code = uniqueCode();
        ConstructionMaterialTypeEntity type = createTestType(code, "старое имя", "stara nazwa", true);
        entityManager.flush();

        // Body has NO code field — names + active only.
        mockMvc.perform(put("/api/construction-material-types/" + type.getId())
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
        mockMvc.perform(get("/api/construction-material-types/" + type.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("PUT /api/construction-material-types/{id} - update with a different code keeps the original code")
    void updateType_withDifferentCode_keepsOriginalCode() throws Exception {
        String code = uniqueCode();
        ConstructionMaterialTypeEntity type = createTestType(code, "старое имя", "stara nazwa", true);
        entityManager.flush();

        // Body attempts to change code — it must be ignored (code is immutable).
        mockMvc.perform(put("/api/construction-material-types/" + type.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "totally-different-code",
                                    "nameRU": "новое имя",
                                    "namePL": "nowa nazwa",
                                    "active": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));

        mockMvc.perform(get("/api/construction-material-types/" + type.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/construction-material-types/{id} - deletes; subsequent read is 404")
    void deleteType_returns204ThenNotFound() throws Exception {
        String code = uniqueCode();
        ConstructionMaterialTypeEntity type = createTestType(code, "удаляемая", "usuwana", true);
        entityManager.flush();

        mockMvc.perform(delete("/api/construction-material-types/" + type.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/construction-material-types/" + type.getId()))
                .andExpect(status().isNotFound());
    }
}
