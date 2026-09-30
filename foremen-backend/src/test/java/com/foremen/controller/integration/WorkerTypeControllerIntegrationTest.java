package com.foremen.controller.integration;

import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.WorkerTypeEntity;
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

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FOR-05-06 (task 2.2) — CRUD + localized-validation integration tests for
 * {@link com.foremen.controller.WorkerTypeController} (ABAC resource {@code WORKER_TYPES}).
 *
 * <p>Mirrors {@link VatRateControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc against a
 * Testcontainers PostgreSQL, Hibernate {@code create-drop} DDL (Liquibase disabled in the
 * {@code integration-test} profile — so the {@code 125} seed does <em>not</em> pre-populate a BASE
 * tier here; every base-related scenario seeds its own base row), {@code @WithMockUser(roles="ADMIN")}
 * (so the ABAC matrix is bypassed and the CRUD path itself is exercised), and {@code @Transactional}
 * rollback for isolation.
 *
 * <p>Covers:
 * <ul>
 *   <li><b>CRUD happy-path</b> — create → read → list → update → delete a worker type via the API.</li>
 *   <li><b>Validation rejections with localized codes</b> — a negative {@code tierPct} rejects with
 *       {@code error.worker.type.tierPct.range}; a base share outside {@code (0, 1]} rejects with
 *       {@code error.worker.type.base.share.range}; creating a second base tier rejects with
 *       {@code error.worker.type.base.duplicate}; clearing the only base tier rejects with
 *       {@code error.worker.type.base.required}. The default (no {@code Accept-Language}) bundle is PL,
 *       so assertions match the resolved Polish message text for each code.</li>
 * </ul>
 *
 * <p>Validates: Requirements 1.2, 1.5
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class WorkerTypeControllerIntegrationTest {

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
    private WorkerTypeDao workerTypeDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String uniqueCode() {
        return "WT" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    /** Persists a worker type directly (bypassing the service validation) for read/precondition setup. */
    private WorkerTypeEntity persistWorkerType(String code, BigDecimal tierPct, boolean base,
                                               String nameRU, String namePL) {
        WorkerTypeEntity entity = new WorkerTypeEntity();
        entity.setCode(code);
        entity.setTierPct(tierPct);
        entity.setBase(base);
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);
        entity.setOrderNo(1);
        entity.setActive(true);
        WorkerTypeEntity saved = workerTypeDao.save(entity);
        entityManager.flush();
        return saved;
    }

    // --- CRUD happy-path ---

    @Test
    @DisplayName("POST /api/worker-types — creates a non-base worker type and echoes its fields")
    void createWorkerType_returnsCreatedWorkerType() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/worker-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Наёмный без инструмента",
                                    "namePL": "Najemny bez narzędzi",
                                    "tierPct": 0.1000,
                                    "base": false,
                                    "orderNo": 2,
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Наёмный без инструмента"))
                .andExpect(jsonPath("$.namePL").value("Najemny bez narzędzi"))
                .andExpect(jsonPath("$.tierPct").value(0.1000))
                .andExpect(jsonPath("$.base").value(false))
                .andExpect(jsonPath("$.orderNo").value(2))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/worker-types/{id} — returns the extended DTO after create")
    void findWorkerTypeById_returnsExtendedModel() throws Exception {
        String code = uniqueCode();
        WorkerTypeEntity entity = persistWorkerType(code, new BigDecimal("0.2650"), false,
                "ИП со всем инструментом", "JDG z pełnym narzędziem");

        mockMvc.perform(get("/api/worker-types/" + entity.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entity.getId()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("ИП со всем инструментом"))
                .andExpect(jsonPath("$.namePL").value("JDG z pełnym narzędziem"))
                .andExpect(jsonPath("$.tierPct").value(0.2650))
                .andExpect(jsonPath("$.base").value(false))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/worker-types — the created worker type appears in the paginated list")
    void findWorkerTypes_returnsPaginatedResult() throws Exception {
        String code = uniqueCode();
        persistWorkerType(code, new BigDecimal("0.6500"), false, "Фирма", "Firma");

        mockMvc.perform(get("/api/worker-types")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].base").value(contains(false)))
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].active").value(contains(true)));
    }

    @Test
    @DisplayName("GET /api/worker-types/{nonExistentId} — returns 404")
    void findWorkerTypeById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/worker-types/99999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/worker-types/{id} — updates tierPct/names/active; code stays immutable")
    void updateWorkerType_updatesFieldsKeepsCode() throws Exception {
        String code = uniqueCode();
        WorkerTypeEntity entity = persistWorkerType(code, new BigDecimal("0.1000"), false,
                "старое имя", "stara nazwa");

        // Body has NO code field — tierPct + names + base + active only.
        mockMvc.perform(put("/api/worker-types/" + entity.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "новое имя",
                                    "namePL": "nowa nazwa",
                                    "tierPct": 0.3000,
                                    "base": false,
                                    "orderNo": 5,
                                    "active": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.tierPct").value(0.3000))
                .andExpect(jsonPath("$.active").value(false));

        // Confirm via a fresh read that code was untouched and fields updated.
        mockMvc.perform(get("/api/worker-types/" + entity.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.tierPct").value(0.3000))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("DELETE /api/worker-types/{id} — deletes; subsequent read is 404")
    void deleteWorkerType_returns204ThenNotFound() throws Exception {
        String code = uniqueCode();
        WorkerTypeEntity entity = persistWorkerType(code, new BigDecimal("0.6500"), false,
                "удаляемый", "usuwany");

        mockMvc.perform(delete("/api/worker-types/" + entity.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/worker-types/" + entity.getId()))
                .andExpect(status().isNotFound());
    }

    // --- Validation rejections (localized codes; default bundle is PL) ---

    @Test
    @DisplayName("POST tierPct < 0 → 400 error.worker.type.tierPct.range (localized)")
    void createWorkerType_negativeTierPct_rejectsWithTierPctRange() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/worker-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "имя",
                                    "namePL": "nazwa",
                                    "tierPct": -0.1000,
                                    "base": false,
                                    "orderNo": 1,
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST base share > 1 → 400 error.worker.type.base.share.range (localized)")
    void createWorkerType_baseShareOutOfRange_rejectsWithBaseShareRange() throws Exception {
        String code = uniqueCode();
        // base=true with tierPct=1.5000 (outside (0,1]) — passes @DecimalMin("0") request check,
        // rejected by the service-layer base-share rule.
        mockMvc.perform(post("/api/worker-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "База",
                                    "namePL": "Baza",
                                    "tierPct": 1.5000,
                                    "base": true,
                                    "orderNo": 1,
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Udział poziomu bazowego musi mieścić się w przedziale (0, 1]."));
    }

    @Test
    @DisplayName("POST base share == 0 → 400 error.worker.type.base.share.range (lower bound exclusive)")
    void createWorkerType_baseShareZero_rejectsWithBaseShareRange() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/worker-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "База",
                                    "namePL": "Baza",
                                    "tierPct": 0.0000,
                                    "base": true,
                                    "orderNo": 1,
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Udział poziomu bazowego musi mieścić się w przedziale (0, 1]."));
    }

    @Test
    @DisplayName("POST second base tier → 400 error.worker.type.base.duplicate (localized)")
    void createWorkerType_secondBase_rejectsWithBaseDuplicate() throws Exception {
        // Precondition: one valid base tier already exists.
        persistWorkerType(uniqueCode(), new BigDecimal("0.4000"), true, "База", "Baza");

        String code = uniqueCode();
        mockMvc.perform(post("/api/worker-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Вторая база",
                                    "namePL": "Druga baza",
                                    "tierPct": 0.5000,
                                    "base": true,
                                    "orderNo": 2,
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Może istnieć tylko jeden bazowy typ pracownika."));
    }

    @Test
    @DisplayName("PUT clearing the only base tier → 400 error.worker.type.base.required (localized)")
    void updateWorkerType_clearOnlyBase_rejectsWithBaseRequired() throws Exception {
        String code = uniqueCode();
        // The only base tier in the dictionary.
        WorkerTypeEntity base = persistWorkerType(code, new BigDecimal("0.4000"), true, "База", "Baza");

        // Attempt to flip base=false — would leave the dictionary with no base tier.
        mockMvc.perform(put("/api/worker-types/" + base.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "База",
                                    "namePL": "Baza",
                                    "tierPct": 0.4000,
                                    "base": false,
                                    "orderNo": 1,
                                    "active": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Musi istnieć dokładnie jeden bazowy typ pracownika; nie można usunąć ostatniego."));
    }
}
