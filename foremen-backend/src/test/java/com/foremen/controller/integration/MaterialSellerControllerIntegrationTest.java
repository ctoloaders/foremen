package com.foremen.controller.integration;

import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.model.MaterialSellerEntity;
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
 * Integration tests for MaterialSellerController CRUD operations.
 * <p>
 * Mirrors {@link MaterialTypeControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc
 * against a Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation.
 * <p>
 * Covers: create → list → read → update → delete, i18n {@code name} resolution
 * ({@code Accept-Language: ru} → nameRU, {@code pl}/absent → namePL), {@code code}
 * immutability on update, create validation ({@code 400} on blank code/nameRU/namePL),
 * PLUS the optional {@code website} field handling: null accepted, exactly 255 chars
 * accepted, 256 chars rejected {@code 400} (boundary), and {@code website} updatable.
 * <p>
 * Validates Requirements 12.1, 2.5, 2.6, 2.7.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class MaterialSellerControllerIntegrationTest {

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
    private MaterialSellerDao materialSellerDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String uniqueCode() {
        return "ms" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private MaterialSellerEntity createTestSeller(String code, String nameRU, String namePL, boolean active, String website) {
        MaterialSellerEntity seller = new MaterialSellerEntity();
        seller.setCode(code);
        seller.setNameRU(nameRU);
        seller.setNamePL(namePL);
        seller.setActive(active);
        seller.setWebsite(website);
        return materialSellerDao.save(seller);
    }

    /** A String of exactly {@code length} 'a' characters. */
    private static String repeat(int length) {
        return "a".repeat(length);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/material-sellers - creates a seller and echoes fields incl. website")
    void createSeller_returnsCreatedSeller() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/material-sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Леруа Мерлен",
                                    "namePL": "Leroy Merlin",
                                    "active": true,
                                    "website": "https://www.leroymerlin.pl"
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Леруа Мерлен"))
                .andExpect(jsonPath("$.namePL").value("Leroy Merlin"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.website").value("https://www.leroymerlin.pl"));
    }

    @Test
    @DisplayName("POST /api/material-sellers - missing required fields returns 400")
    void createSeller_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/material-sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "namePL": "tylko nazwa PL"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/material-sellers - blank code returns 400")
    void createSeller_blankCode_returns400() throws Exception {
        mockMvc.perform(post("/api/material-sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "",
                                    "nameRU": "Касторама",
                                    "namePL": "Castorama",
                                    "active": true
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/material-sellers - blank nameRU returns 400")
    void createSeller_blankNameRU_returns400() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/material-sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "",
                                    "namePL": "Castorama",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/material-sellers - blank namePL returns 400")
    void createSeller_blankNamePL_returns400() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/material-sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Касторама",
                                    "namePL": "",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isBadRequest());
    }

    // --- website handling (boundary: null / 255 / 256) ---

    @Test
    @DisplayName("POST /api/material-sellers - null website is accepted")
    void createSeller_nullWebsite_accepted() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/material-sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Без сайта",
                                    "namePL": "Bez strony",
                                    "active": true,
                                    "website": null
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.website").value(nullValue()));
    }

    @Test
    @DisplayName("POST /api/material-sellers - absent website is accepted (null)")
    void createSeller_absentWebsite_accepted() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/material-sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Без сайта",
                                    "namePL": "Bez strony",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.website").value(nullValue()));
    }

    @Test
    @DisplayName("POST /api/material-sellers - website of exactly 255 chars is accepted")
    void createSeller_website255_accepted() throws Exception {
        String code = uniqueCode();
        String website = repeat(255);
        mockMvc.perform(post("/api/material-sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Максимальный сайт",
                                    "namePL": "Maksymalna strona",
                                    "active": true,
                                    "website": "%s"
                                }
                                """.formatted(code, website)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.website").value(website));
    }

    @Test
    @DisplayName("POST /api/material-sellers - website of 256 chars returns 400")
    void createSeller_website256_returns400() throws Exception {
        String code = uniqueCode();
        String website = repeat(256);
        mockMvc.perform(post("/api/material-sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Слишком длинный сайт",
                                    "namePL": "Zbyt długa strona",
                                    "active": true,
                                    "website": "%s"
                                }
                                """.formatted(code, website)))
                .andExpect(status().isBadRequest());
    }

    // --- READ / LIST ---

    @Test
    @DisplayName("GET /api/material-sellers - returns paginated sellers with name/code/active/website")
    void findSellers_returnsPaginatedResult() throws Exception {
        String code = uniqueCode();
        createTestSeller(code, "Касторама", "Castorama", true, "https://www.castorama.pl");
        entityManager.flush();

        mockMvc.perform(get("/api/material-sellers")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].active").value(contains(true)))
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].website").value(contains("https://www.castorama.pl")));
    }

    @Test
    @DisplayName("GET /api/material-sellers/{id} - returns extended DTO (nameRU/namePL/code/active/website)")
    void findSellerById_returnsExtendedModel() throws Exception {
        String code = uniqueCode();
        MaterialSellerEntity seller = createTestSeller(code, "Оби", "Obi", true, "https://www.obi.pl");
        entityManager.flush();

        mockMvc.perform(get("/api/material-sellers/" + seller.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(seller.getId()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Оби"))
                .andExpect(jsonPath("$.namePL").value("Obi"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.website").value("https://www.obi.pl"));
    }

    @Test
    @DisplayName("GET /api/material-sellers/{nonExistentId} - returns 404")
    void findSellerById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/material-sellers/99999"))
                .andExpect(status().isNotFound());
    }

    // --- i18n name resolution on the list endpoint ---

    @Test
    @DisplayName("GET /api/material-sellers - Accept-Language: ru resolves name to nameRU")
    void listWithRussianLocale_resolvesNameToRU() throws Exception {
        String code = uniqueCode();
        createTestSeller(code, "Леруа Мерлен", "Leroy Merlin", true, null);
        entityManager.flush();

        mockMvc.perform(get("/api/material-sellers")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Леруа Мерлен")));
    }

    @Test
    @DisplayName("GET /api/material-sellers - Accept-Language: pl resolves name to namePL")
    void listWithPolishLocale_resolvesNameToPL() throws Exception {
        String code = uniqueCode();
        createTestSeller(code, "Леруа Мерлен", "Leroy Merlin", true, null);
        entityManager.flush();

        mockMvc.perform(get("/api/material-sellers")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Leroy Merlin")));
    }

    @Test
    @DisplayName("GET /api/material-sellers - absent Accept-Language falls back to namePL")
    void listWithoutLocale_fallsBackToPL() throws Exception {
        String code = uniqueCode();
        createTestSeller(code, "Касторама", "Castorama", true, null);
        entityManager.flush();

        mockMvc.perform(get("/api/material-sellers")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Castorama")));
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/material-sellers/{id} - updates names/active/website; code is immutable")
    void updateSeller_updatesFieldsKeepsCode() throws Exception {
        String code = uniqueCode();
        MaterialSellerEntity seller = createTestSeller(code, "старое имя", "stara nazwa", true, "https://old.example.pl");
        entityManager.flush();

        // Body has NO code field — names + active + website only.
        mockMvc.perform(put("/api/material-sellers/" + seller.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "новое имя",
                                    "namePL": "nowa nazwa",
                                    "active": false,
                                    "website": "https://new.example.pl"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.website").value("https://new.example.pl"))
                // code must remain unchanged after update (immutable).
                .andExpect(jsonPath("$.code").value(code));

        // Confirm via a fresh read that the code was not altered and website updated.
        mockMvc.perform(get("/api/material-sellers/" + seller.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.website").value("https://new.example.pl"));
    }

    @Test
    @DisplayName("PUT /api/material-sellers/{id} - update with a different code keeps the original code")
    void updateSeller_withDifferentCode_keepsOriginalCode() throws Exception {
        String code = uniqueCode();
        MaterialSellerEntity seller = createTestSeller(code, "старое имя", "stara nazwa", true, null);
        entityManager.flush();

        // Body attempts to change code — it must be ignored (code is immutable).
        mockMvc.perform(put("/api/material-sellers/" + seller.getId())
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

        mockMvc.perform(get("/api/material-sellers/" + seller.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    @DisplayName("PUT /api/material-sellers/{id} - website of 256 chars returns 400")
    void updateSeller_website256_returns400() throws Exception {
        String code = uniqueCode();
        MaterialSellerEntity seller = createTestSeller(code, "имя", "nazwa", true, null);
        entityManager.flush();

        String website = repeat(256);
        mockMvc.perform(put("/api/material-sellers/" + seller.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "имя",
                                    "namePL": "nazwa",
                                    "active": true,
                                    "website": "%s"
                                }
                                """.formatted(website)))
                .andExpect(status().isBadRequest());
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/material-sellers/{id} - deletes; subsequent read is 404")
    void deleteSeller_returns204ThenNotFound() throws Exception {
        String code = uniqueCode();
        MaterialSellerEntity seller = createTestSeller(code, "удаляемая", "usuwana", true, null);
        entityManager.flush();

        mockMvc.perform(delete("/api/material-sellers/" + seller.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/material-sellers/" + seller.getId()))
                .andExpect(status().isNotFound());
    }
}
