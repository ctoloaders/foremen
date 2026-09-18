package com.foremen.controller.integration;

import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MaterialProducerDao;
import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Integration tests for {@code ConstructionMaterialController} CRUD operations (FOR-04-17, task 15.2).
 * <p>
 * Mirrors {@link ConstructionMaterialTypeControllerIntegrationTest} EXACTLY for the boot + auth
 * setup: {@code @SpringBootTest} + MockMvc against a Testcontainers PostgreSQL, {@code create-drop}
 * DDL, {@code @Import(MockMvcSecurityConfig.class)}, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation.
 * <p>
 * Covers:
 * <ul>
 *   <li>CRUD against {@code /api/construction-materials} with a required {@code type}, optional
 *       {@code producer}/{@code seller}, and a required {@code packages} set (≥ 1);</li>
 *   <li>empty-{@code packages} rejection (client-error, nothing persisted) — Requirement 4.2/4.3;</li>
 *   <li>reference filters {@code type.id} and {@code packages.id} on the list endpoint riding the
 *       FOR-04-01 query grammar — Requirement 4.9;</li>
 *   <li>i18n {@code name} resolution ({@code Accept-Language: ru} → nameRU, {@code pl}/absent →
 *       namePL) — Requirement 4.8.</li>
 * </ul>
 * <p>
 * Mandatory references (a {@code ConstructionMaterialType}, a {@code MeasurementUnit}, a
 * {@code Currency}, and ≥ 1 {@code OfferPackage}) plus optional {@code MaterialProducer}/
 * {@code MaterialSeller} are seeded per-test in {@link #seedReferences()}.
 * <p>
 * Validates Requirements 12.2, 4.9.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class ConstructionMaterialControllerIntegrationTest {

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

    /** Per-run unique code suffix so seeded reference rows never collide across tests. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConstructionMaterialTypeDao typeDao;

    @Autowired
    private MeasurementUnitDao unitDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Autowired
    private OfferPackageDao offerPackageDao;

    @Autowired
    private MaterialProducerDao producerDao;

    @Autowired
    private MaterialSellerDao sellerDao;

    @PersistenceContext
    private EntityManager entityManager;

    // Seeded reference ids reused by the scenarios.
    private Long typeId;
    private Long typeId2;
    private Long unitId;
    private Long currencyId;
    private Long packageId;
    private Long packageId2;
    private Long producerId;
    private Long sellerId;

    private String unique() {
        return System.nanoTime() + "_" + COUNTER.incrementAndGet();
    }

    @BeforeEach
    void seedReferences() {
        String u = unique();

        ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
        type.setCode("cm_type_" + u);
        type.setNameRU("Краска");
        type.setNamePL("Farba");
        type.setActive(true);
        typeId = typeDao.save(type).getId();

        ConstructionMaterialTypeEntity type2 = new ConstructionMaterialTypeEntity();
        type2.setCode("cm_type2_" + u);
        type2.setNameRU("Клей");
        type2.setNamePL("Klej");
        type2.setActive(true);
        typeId2 = typeDao.save(type2).getId();

        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("cm_unit_" + u);
        unit.setNameRU("литр");
        unit.setNamePL("litr");
        unit.setActive(true);
        unitId = unitDao.save(unit).getId();

        CurrencyEntity currency = new CurrencyEntity();
        currency.setCode("CM" + (COUNTER.get() % 100));
        currency.setSymbol("zł");
        currency.setNameRU("Злотый");
        currency.setNamePL("Złoty");
        currency.setActive(true);
        currencyId = currencyDao.save(currency).getId();

        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode("cm_pkg_" + u);
        pkg.setOrderNo(1);
        pkg.setNameRU("Пакет A");
        pkg.setNamePL("Pakiet A");
        pkg.setActive(true);
        packageId = offerPackageDao.save(pkg).getId();

        OfferPackageEntity pkg2 = new OfferPackageEntity();
        pkg2.setCode("cm_pkg2_" + u);
        pkg2.setOrderNo(2);
        pkg2.setNameRU("Пакет B");
        pkg2.setNamePL("Pakiet B");
        pkg2.setActive(true);
        packageId2 = offerPackageDao.save(pkg2).getId();

        MaterialProducerEntity producer = new MaterialProducerEntity();
        producer.setCode("cm_prod_" + u);
        producer.setNameRU("Производитель");
        producer.setNamePL("Producent");
        producer.setActive(true);
        producerId = producerDao.save(producer).getId();

        MaterialSellerEntity seller = new MaterialSellerEntity();
        seller.setCode("cm_sell_" + u);
        seller.setNameRU("Продавец");
        seller.setNamePL("Sprzedawca");
        seller.setActive(true);
        sellerId = sellerDao.save(seller).getId();

        entityManager.flush();
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/construction-materials - creates a material with required type + packages, optional producer/seller")
    void createMaterial_returnsCreatedMaterial() throws Exception {
        mockMvc.perform(post("/api/construction-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "Краска белая",
                                    "namePL": "Farba biała",
                                    "typeId": %d,
                                    "producerId": %d,
                                    "sellerId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "currencyId": %d,
                                    "retailNet": 100.00,
                                    "active": true
                                }
                                """.formatted(typeId, producerId, sellerId, packageId, unitId, currencyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                // The create/update response carries the raw reference ids (the localized RefDto
                // refs + name are a read-only concern populated only on the read/list path).
                .andExpect(jsonPath("$.typeId").value(typeId))
                .andExpect(jsonPath("$.producerId").value(producerId))
                .andExpect(jsonPath("$.sellerId").value(sellerId))
                .andExpect(jsonPath("$.unitId").value(unitId))
                .andExpect(jsonPath("$.currencyId").value(currencyId))
                .andExpect(jsonPath("$.offerPackageIds[?(@ == " + packageId + ")]").exists())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/construction-materials - creates without optional producer/seller")
    void createMaterial_withoutOptionalReferences_succeeds() throws Exception {
        mockMvc.perform(post("/api/construction-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "Краска серая",
                                    "namePL": "Farba szara",
                                    "typeId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "currencyId": %d
                                }
                                """.formatted(typeId, packageId, unitId, currencyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.typeId").value(typeId))
                .andExpect(jsonPath("$.producerId").value(nullValue()))
                .andExpect(jsonPath("$.sellerId").value(nullValue()));
    }

    @Test
    @DisplayName("POST /api/construction-materials - empty packages set is rejected with a client error")
    void createMaterial_emptyPackages_returnsClientError() throws Exception {
        mockMvc.perform(post("/api/construction-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "Краска",
                                    "namePL": "Farba",
                                    "typeId": %d,
                                    "offerPackageIds": [],
                                    "unitId": %d,
                                    "currencyId": %d
                                }
                                """.formatted(typeId, unitId, currencyId)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST /api/construction-materials - missing type is rejected with a client error")
    void createMaterial_missingType_returnsClientError() throws Exception {
        mockMvc.perform(post("/api/construction-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "Краска",
                                    "namePL": "Farba",
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "currencyId": %d
                                }
                                """.formatted(packageId, unitId, currencyId)))
                .andExpect(status().is4xxClientError());
    }

    // --- READ / UPDATE / DELETE ---

    @Test
    @DisplayName("GET /api/construction-materials/{id} - returns extended DTO with raw reference ids")
    void findMaterialById_returnsExtendedModel() throws Exception {
        Long id = createMaterial("Грунтовка", "Grunt", typeId, packageId);

        mockMvc.perform(get("/api/construction-materials/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.typeId").value(typeId))
                .andExpect(jsonPath("$.unitId").value(unitId))
                .andExpect(jsonPath("$.currencyId").value(currencyId))
                .andExpect(jsonPath("$.offerPackageIds[?(@ == " + packageId + ")]").exists());
    }

    @Test
    @DisplayName("GET /api/construction-materials/{nonExistentId} - returns 404")
    void findMaterialById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/construction-materials/99999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/construction-materials/{id} - updates names, references and packages")
    void updateMaterial_updatesFields() throws Exception {
        Long id = createMaterial("старое имя", "stara nazwa", typeId, packageId);

        mockMvc.perform(put("/api/construction-materials/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "новое имя",
                                    "namePL": "nowa nazwa",
                                    "typeId": %d,
                                    "offerPackageIds": [%d, %d],
                                    "unitId": %d,
                                    "currencyId": %d,
                                    "retailNet": 250.00,
                                    "active": false
                                }
                                """.formatted(typeId2, packageId, packageId2, unitId, currencyId)))
                .andExpect(status().isOk())
                // The update response carries the raw reference ids + active (RefDto refs + name and
                // the resolved package set are read-path concerns, verified via the follow-up GET).
                .andExpect(jsonPath("$.typeId").value(typeId2))
                .andExpect(jsonPath("$.active").value(false));

        entityManager.flush();
        entityManager.clear();

        // Confirm the update persisted via the single-read extended DTO: the raw reference ids,
        // both packages, and the flipped active flag.
        mockMvc.perform(get("/api/construction-materials/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typeId").value(typeId2))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.offerPackageIds.length()").value(2));

        // The localized name (PL fallback) is verified on the list endpoint, whose list DTO resolves
        // the i18n name.
        mockMvc.perform(get("/api/construction-materials")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")].name").value(contains("nowa nazwa")));
    }

    @Test
    @DisplayName("PUT /api/construction-materials/{id} - update with an empty packages set is rejected")
    void updateMaterial_emptyPackages_returnsClientError() throws Exception {
        Long id = createMaterial("имя", "nazwa", typeId, packageId);

        mockMvc.perform(put("/api/construction-materials/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "имя",
                                    "namePL": "nazwa",
                                    "typeId": %d,
                                    "offerPackageIds": [],
                                    "unitId": %d,
                                    "currencyId": %d
                                }
                                """.formatted(typeId, unitId, currencyId)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("DELETE /api/construction-materials/{id} - deletes; subsequent read is 404")
    void deleteMaterial_returns204ThenNotFound() throws Exception {
        Long id = createMaterial("удаляемая", "usuwana", typeId, packageId);

        mockMvc.perform(delete("/api/construction-materials/" + id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/construction-materials/" + id))
                .andExpect(status().isNotFound());
    }

    // --- REFERENCE FILTERS (Requirement 4.9, FOR-04-01 query grammar) ---

    @Test
    @DisplayName("GET /api/construction-materials?query=type.id==... - filters by type reference")
    void listFilteredByTypeId_returnsOnlyMatching() throws Exception {
        Long matching = createMaterial("тип-1", "typ-1", typeId, packageId);
        Long other = createMaterial("тип-2", "typ-2", typeId2, packageId);
        entityManager.flush();

        mockMvc.perform(get("/api/construction-materials")
                        .param("query", "type.id==" + typeId)
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + matching + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + other + ")]").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/construction-materials?query=packages.id==... - filters by package (collection path) reference")
    void listFilteredByPackageId_returnsOnlyMatching() throws Exception {
        // 'matching' belongs to packageId2; 'other' belongs only to packageId.
        Long matching = createMaterial("пакет-1", "pakiet-1", typeId, packageId2);
        Long other = createMaterial("пакет-2", "pakiet-2", typeId, packageId);
        entityManager.flush();

        mockMvc.perform(get("/api/construction-materials")
                        .param("query", "packages.id==" + packageId2)
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + matching + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + other + ")]").doesNotExist());
    }

    // --- i18n name resolution ---

    @Test
    @DisplayName("GET /api/construction-materials - Accept-Language: ru resolves name to nameRU")
    void listWithRussianLocale_resolvesNameToRU() throws Exception {
        Long id = createMaterial("Шпаклёвка", "Szpachla", typeId, packageId);
        entityManager.flush();

        mockMvc.perform(get("/api/construction-materials")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")].name").value(contains("Шпаклёвка")));
    }

    @Test
    @DisplayName("GET /api/construction-materials - Accept-Language: pl resolves name to namePL")
    void listWithPolishLocale_resolvesNameToPL() throws Exception {
        Long id = createMaterial("Шпаклёвка", "Szpachla", typeId, packageId);
        entityManager.flush();

        mockMvc.perform(get("/api/construction-materials")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")].name").value(contains("Szpachla")));
    }

    @Test
    @DisplayName("GET /api/construction-materials - absent Accept-Language falls back to namePL")
    void listWithoutLocale_fallsBackToPL() throws Exception {
        Long id = createMaterial("Краска", "Farba", typeId, packageId);
        entityManager.flush();

        mockMvc.perform(get("/api/construction-materials")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")].name").value(contains("Farba")));
    }

    // --- helpers ---

    /**
     * Creates a construction material via the API and returns its generated id. Uses the given type
     * and a single package plus the shared seeded {@code unitId}/{@code currencyId}.
     */
    private Long createMaterial(String nameRU, String namePL, Long typeId, Long packageId) throws Exception {
        String body = mockMvc.perform(post("/api/construction-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "%s",
                                    "namePL": "%s",
                                    "typeId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "currencyId": %d,
                                    "retailNet": 100.00
                                }
                                """.formatted(nameRU, namePL, typeId, packageId, unitId, currencyId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }
}
