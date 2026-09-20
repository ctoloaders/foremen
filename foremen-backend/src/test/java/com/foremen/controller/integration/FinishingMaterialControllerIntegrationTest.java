package com.foremen.controller.integration;

import com.foremen.dao.MaterialCategoryDao;
import com.foremen.dao.MaterialDao;
import com.foremen.dao.MaterialProducerDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialTypeEntity;
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
 * Integration tests for {@code FinishingMaterialController} CRUD operations (FOR-04-18, task 9.1).
 * <p>
 * Mirrors {@link ConstructionMaterialControllerIntegrationTest} EXACTLY for the boot + auth setup:
 * {@code @SpringBootTest} + MockMvc against a Testcontainers PostgreSQL, {@code create-drop} DDL,
 * {@code @Import(MockMvcSecurityConfig.class)}, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation, and per-run unique reference codes.
 * <p>
 * Covers:
 * <ul>
 *   <li>CRUD against {@code /api/finishing-materials} with a required {@code category}/
 *       {@code material}/{@code unit}, optional {@code type}/{@code producer}, and a required
 *       {@code packages} set (≥ 1) — Requirement 2.2/2.3;</li>
 *   <li>empty-{@code packages} rejection on create and update (client-error, nothing persisted) —
 *       Requirement 2.3;</li>
 *   <li>{@code model}/{@code sku} 255 (accepted) vs 256 (rejected) and {@code link} 1024 (accepted)
 *       vs 1025 (rejected) length boundaries — Requirement 2.6/2.7;</li>
 *   <li>the derived {@code label} on read ({@code material} name + {@code " — "} + {@code model}) —
 *       Requirement 2.9;</li>
 *   <li>reference filters {@code category.id}/{@code material.id}/{@code type.id}/
 *       {@code producer.id}/{@code unit.id}/{@code packages.id} on the list endpoint riding the
 *       FOR-04-01 query grammar — Requirement 2.10.</li>
 * </ul>
 * <p>
 * Mandatory references (a {@code MaterialCategory}, a {@code Material}, a {@code MeasurementUnit},
 * and ≥ 1 {@code OfferPackage}) plus optional {@code MaterialType}/{@code MaterialProducer} are
 * seeded per-test in {@link #seedReferences()}.
 * <p>
 * Validates Requirements 8.1, 8.2, 2.6, 2.7, 2.9, 2.10.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class FinishingMaterialControllerIntegrationTest {

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
    private MaterialCategoryDao categoryDao;

    @Autowired
    private MaterialDao materialDao;

    @Autowired
    private MaterialTypeDao typeDao;

    @Autowired
    private MaterialProducerDao producerDao;

    @Autowired
    private MeasurementUnitDao unitDao;

    @Autowired
    private OfferPackageDao offerPackageDao;

    @PersistenceContext
    private EntityManager entityManager;

    // Seeded reference ids reused by the scenarios.
    private Long categoryId;
    private Long categoryId2;
    private Long materialId;
    private Long materialId2;
    private Long typeId;
    private Long typeId2;
    private Long producerId;
    private Long producerId2;
    private Long unitId;
    private Long unitId2;
    private Long packageId;
    private Long packageId2;

    private String unique() {
        return System.nanoTime() + "_" + COUNTER.incrementAndGet();
    }

    @BeforeEach
    void seedReferences() {
        String u = unique();

        MaterialCategoryEntity category = new MaterialCategoryEntity();
        category.setCode("fm_cat_" + u);
        category.setNameRU("Категория A");
        category.setNamePL("Kategoria A");
        category.setActive(true);
        categoryId = categoryDao.save(category).getId();

        MaterialCategoryEntity category2 = new MaterialCategoryEntity();
        category2.setCode("fm_cat2_" + u);
        category2.setNameRU("Категория B");
        category2.setNamePL("Kategoria B");
        category2.setActive(true);
        categoryId2 = categoryDao.save(category2).getId();

        MaterialEntity material = new MaterialEntity();
        material.setCode("fm_mat_" + u);
        material.setNameRU("Пол");
        material.setNamePL("Podłoga");
        material.setActive(true);
        materialId = materialDao.save(material).getId();

        MaterialEntity material2 = new MaterialEntity();
        material2.setCode("fm_mat2_" + u);
        material2.setNameRU("Стена");
        material2.setNamePL("Ściana");
        material2.setActive(true);
        materialId2 = materialDao.save(material2).getId();

        MaterialTypeEntity type = new MaterialTypeEntity();
        type.setCode("fm_type_" + u);
        type.setNameRU("Ламинат");
        type.setNamePL("Laminat");
        type.setActive(true);
        typeId = typeDao.save(type).getId();

        MaterialTypeEntity type2 = new MaterialTypeEntity();
        type2.setCode("fm_type2_" + u);
        type2.setNameRU("Плитка");
        type2.setNamePL("Płytka");
        type2.setActive(true);
        typeId2 = typeDao.save(type2).getId();

        MaterialProducerEntity producer = new MaterialProducerEntity();
        producer.setCode("fm_prod_" + u);
        producer.setNameRU("Производитель A");
        producer.setNamePL("Producent A");
        producer.setActive(true);
        producerId = producerDao.save(producer).getId();

        MaterialProducerEntity producer2 = new MaterialProducerEntity();
        producer2.setCode("fm_prod2_" + u);
        producer2.setNameRU("Производитель B");
        producer2.setNamePL("Producent B");
        producer2.setActive(true);
        producerId2 = producerDao.save(producer2).getId();

        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("fm_unit_" + u);
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        unitId = unitDao.save(unit).getId();

        MeasurementUnitEntity unit2 = new MeasurementUnitEntity();
        unit2.setCode("fm_unit2_" + u);
        unit2.setNameRU("шт");
        unit2.setNamePL("szt");
        unit2.setActive(true);
        unitId2 = unitDao.save(unit2).getId();

        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode("fm_pkg_" + u);
        pkg.setOrderNo(1);
        pkg.setNameRU("Пакет A");
        pkg.setNamePL("Pakiet A");
        pkg.setActive(true);
        packageId = offerPackageDao.save(pkg).getId();

        OfferPackageEntity pkg2 = new OfferPackageEntity();
        pkg2.setCode("fm_pkg2_" + u);
        pkg2.setOrderNo(2);
        pkg2.setNameRU("Пакет B");
        pkg2.setNamePL("Pakiet B");
        pkg2.setActive(true);
        packageId2 = offerPackageDao.save(pkg2).getId();

        entityManager.flush();
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/finishing-materials - creates with required category/material/unit + packages, optional type/producer")
    void createMaterial_returnsCreatedMaterial() throws Exception {
        mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "typeId": %d,
                                    "producerId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "model": "Dąb North EL2157",
                                    "sku": "SKU-001",
                                    "features": "AC4 8 mm",
                                    "purchasePrice": 40.00,
                                    "retailGross": 65.00,
                                    "retailNet": 53.61,
                                    "link": "https://example.com/product",
                                    "active": true
                                }
                                """.formatted(categoryId, materialId, typeId, producerId, packageId, unitId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                // The create/update response carries the raw reference ids.
                .andExpect(jsonPath("$.categoryId").value(categoryId))
                .andExpect(jsonPath("$.materialId").value(materialId))
                .andExpect(jsonPath("$.typeId").value(typeId))
                .andExpect(jsonPath("$.producerId").value(producerId))
                .andExpect(jsonPath("$.unitId").value(unitId))
                .andExpect(jsonPath("$.offerPackageIds[?(@ == " + packageId + ")]").exists())
                .andExpect(jsonPath("$.model").value("Dąb North EL2157"))
                .andExpect(jsonPath("$.sku").value("SKU-001"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/finishing-materials - creates without optional type/producer")
    void createMaterial_withoutOptionalReferences_succeeds() throws Exception {
        mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "model": "Bez typu"
                                }
                                """.formatted(categoryId, materialId, packageId, unitId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.categoryId").value(categoryId))
                .andExpect(jsonPath("$.materialId").value(materialId))
                .andExpect(jsonPath("$.typeId").value(nullValue()))
                .andExpect(jsonPath("$.producerId").value(nullValue()));
    }

    @Test
    @DisplayName("POST /api/finishing-materials - empty packages set is rejected with a client error")
    void createMaterial_emptyPackages_returnsClientError() throws Exception {
        mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "offerPackageIds": [],
                                    "unitId": %d
                                }
                                """.formatted(categoryId, materialId, unitId)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST /api/finishing-materials - missing category is rejected with a client error")
    void createMaterial_missingCategory_returnsClientError() throws Exception {
        mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "materialId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d
                                }
                                """.formatted(materialId, packageId, unitId)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST /api/finishing-materials - missing material is rejected with a client error")
    void createMaterial_missingMaterial_returnsClientError() throws Exception {
        mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d
                                }
                                """.formatted(categoryId, packageId, unitId)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST /api/finishing-materials - missing unit is rejected with a client error")
    void createMaterial_missingUnit_returnsClientError() throws Exception {
        mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "offerPackageIds": [%d]
                                }
                                """.formatted(categoryId, materialId, packageId)))
                .andExpect(status().is4xxClientError());
    }

    // --- LENGTH BOUNDARIES (Requirement 2.6 model/sku, 2.7 link) ---

    @Test
    @DisplayName("POST /api/finishing-materials - model of 255 chars is accepted, 256 is rejected")
    void createMaterial_modelLengthBoundary() throws Exception {
        // 255 accepted
        mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "model": "%s"
                                }
                                """.formatted(categoryId, materialId, packageId, unitId, "M".repeat(255))))
                .andExpect(status().isOk());

        // 256 rejected
        mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "model": "%s"
                                }
                                """.formatted(categoryId, materialId, packageId, unitId, "M".repeat(256))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST /api/finishing-materials - sku of 255 chars is accepted, 256 is rejected")
    void createMaterial_skuLengthBoundary() throws Exception {
        // 255 accepted
        mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "sku": "%s"
                                }
                                """.formatted(categoryId, materialId, packageId, unitId, "S".repeat(255))))
                .andExpect(status().isOk());

        // 256 rejected
        mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "sku": "%s"
                                }
                                """.formatted(categoryId, materialId, packageId, unitId, "S".repeat(256))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST /api/finishing-materials - link of 1024 chars is accepted, 1025 is rejected")
    void createMaterial_linkLengthBoundary() throws Exception {
        // 1024 accepted
        mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "link": "%s"
                                }
                                """.formatted(categoryId, materialId, packageId, unitId, "L".repeat(1024))))
                .andExpect(status().isOk());

        // 1025 rejected
        mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "link": "%s"
                                }
                                """.formatted(categoryId, materialId, packageId, unitId, "L".repeat(1025))))
                .andExpect(status().is4xxClientError());
    }

    // --- READ / UPDATE / DELETE ---

    @Test
    @DisplayName("GET /api/finishing-materials/{id} - returns extended DTO with raw reference ids")
    void findMaterialById_returnsExtendedModel() throws Exception {
        Long id = createMaterial(categoryId, materialId, "Model X", packageId);

        mockMvc.perform(get("/api/finishing-materials/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.categoryId").value(categoryId))
                .andExpect(jsonPath("$.materialId").value(materialId))
                .andExpect(jsonPath("$.unitId").value(unitId))
                .andExpect(jsonPath("$.offerPackageIds[?(@ == " + packageId + ")]").exists());
    }

    @Test
    @DisplayName("GET /api/finishing-materials/{nonExistentId} - returns 404")
    void findMaterialById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/finishing-materials/99999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/finishing-materials/{id} - updates references and packages")
    void updateMaterial_updatesFields() throws Exception {
        Long id = createMaterial(categoryId, materialId, "старый", packageId);

        mockMvc.perform(put("/api/finishing-materials/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "typeId": %d,
                                    "offerPackageIds": [%d, %d],
                                    "unitId": %d,
                                    "model": "новый",
                                    "retailNet": 250.00,
                                    "active": false
                                }
                                """.formatted(categoryId2, materialId2, typeId2, packageId, packageId2, unitId2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(categoryId2))
                .andExpect(jsonPath("$.materialId").value(materialId2))
                .andExpect(jsonPath("$.typeId").value(typeId2))
                .andExpect(jsonPath("$.unitId").value(unitId2))
                .andExpect(jsonPath("$.active").value(false));

        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/finishing-materials/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(categoryId2))
                .andExpect(jsonPath("$.materialId").value(materialId2))
                .andExpect(jsonPath("$.typeId").value(typeId2))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.offerPackageIds.length()").value(2));
    }

    @Test
    @DisplayName("PUT /api/finishing-materials/{id} - update with an empty packages set is rejected")
    void updateMaterial_emptyPackages_returnsClientError() throws Exception {
        Long id = createMaterial(categoryId, materialId, "имя", packageId);

        mockMvc.perform(put("/api/finishing-materials/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "offerPackageIds": [],
                                    "unitId": %d
                                }
                                """.formatted(categoryId, materialId, unitId)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("DELETE /api/finishing-materials/{id} - deletes; subsequent read is 404")
    void deleteMaterial_returns204ThenNotFound() throws Exception {
        Long id = createMaterial(categoryId, materialId, "удаляемая", packageId);

        mockMvc.perform(delete("/api/finishing-materials/" + id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/finishing-materials/" + id))
                .andExpect(status().isNotFound());
    }

    // --- DERIVED LABEL (Requirement 2.9) ---

    @Test
    @DisplayName("GET /api/finishing-materials - list exposes the derived label (material name + \" — \" + model)")
    void listExposesDerivedLabel() throws Exception {
        // Default (no Accept-Language) falls back to PL: material namePL == "Podłoga".
        Long id = createMaterial(categoryId, materialId, "Dąb North", packageId);
        entityManager.flush();

        mockMvc.perform(get("/api/finishing-materials")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")].label")
                        .value(contains("Podłoga — Dąb North")));
    }

    // --- REFERENCE FILTERS (Requirement 2.10, FOR-04-01 query grammar) ---

    @Test
    @DisplayName("GET /api/finishing-materials?query=category.id==... - filters by category reference")
    void listFilteredByCategoryId_returnsOnlyMatching() throws Exception {
        Long matching = createMaterial(categoryId, materialId, "cat-1", packageId);
        Long other = createMaterial(categoryId2, materialId, "cat-2", packageId);
        entityManager.flush();

        mockMvc.perform(get("/api/finishing-materials")
                        .param("query", "category.id==" + categoryId)
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + matching + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + other + ")]").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/finishing-materials?query=material.id==... - filters by material reference")
    void listFilteredByMaterialId_returnsOnlyMatching() throws Exception {
        Long matching = createMaterial(categoryId, materialId, "mat-1", packageId);
        Long other = createMaterial(categoryId, materialId2, "mat-2", packageId);
        entityManager.flush();

        mockMvc.perform(get("/api/finishing-materials")
                        .param("query", "material.id==" + materialId)
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + matching + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + other + ")]").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/finishing-materials?query=type.id==... - filters by type reference")
    void listFilteredByTypeId_returnsOnlyMatching() throws Exception {
        Long matching = createMaterialWithType(typeId, "typ-1");
        Long other = createMaterialWithType(typeId2, "typ-2");
        entityManager.flush();

        mockMvc.perform(get("/api/finishing-materials")
                        .param("query", "type.id==" + typeId)
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + matching + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + other + ")]").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/finishing-materials?query=producer.id==... - filters by producer reference")
    void listFilteredByProducerId_returnsOnlyMatching() throws Exception {
        Long matching = createMaterialWithProducer(producerId, "prod-1");
        Long other = createMaterialWithProducer(producerId2, "prod-2");
        entityManager.flush();

        mockMvc.perform(get("/api/finishing-materials")
                        .param("query", "producer.id==" + producerId)
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + matching + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + other + ")]").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/finishing-materials?query=unit.id==... - filters by unit reference")
    void listFilteredByUnitId_returnsOnlyMatching() throws Exception {
        Long matching = createMaterialWithUnit(unitId, "unit-1");
        Long other = createMaterialWithUnit(unitId2, "unit-2");
        entityManager.flush();

        mockMvc.perform(get("/api/finishing-materials")
                        .param("query", "unit.id==" + unitId)
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + matching + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + other + ")]").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/finishing-materials?query=packages.id==... - filters by package (collection path) reference")
    void listFilteredByPackageId_returnsOnlyMatching() throws Exception {
        // 'matching' belongs to packageId2; 'other' belongs only to packageId.
        Long matching = createMaterial(categoryId, materialId, "pakiet-1", packageId2);
        Long other = createMaterial(categoryId, materialId, "pakiet-2", packageId);
        entityManager.flush();

        mockMvc.perform(get("/api/finishing-materials")
                        .param("query", "packages.id==" + packageId2)
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + matching + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + other + ")]").doesNotExist());
    }

    // --- helpers ---

    /**
     * Creates a finishing material via the API and returns its generated id. Uses the given
     * category/material, a single package plus the shared seeded {@code unitId}, and the given model.
     */
    private Long createMaterial(Long categoryId, Long materialId, String model, Long packageId) throws Exception {
        String body = mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "model": "%s",
                                    "retailNet": 100.00
                                }
                                """.formatted(categoryId, materialId, packageId, unitId, model)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    /** Creates a material bound to a specific type (plus shared category/material/unit/package). */
    private Long createMaterialWithType(Long typeId, String model) throws Exception {
        String body = mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "typeId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "model": "%s"
                                }
                                """.formatted(categoryId, materialId, typeId, packageId, unitId, model)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    /** Creates a material bound to a specific producer (plus shared category/material/unit/package). */
    private Long createMaterialWithProducer(Long producerId, String model) throws Exception {
        String body = mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "producerId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "model": "%s"
                                }
                                """.formatted(categoryId, materialId, producerId, packageId, unitId, model)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    /** Creates a material bound to a specific unit (plus shared category/material/package). */
    private Long createMaterialWithUnit(Long unitId, String model) throws Exception {
        String body = mockMvc.perform(post("/api/finishing-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "categoryId": %d,
                                    "materialId": %d,
                                    "offerPackageIds": [%d],
                                    "unitId": %d,
                                    "model": "%s"
                                }
                                """.formatted(categoryId, materialId, packageId, unitId, model)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }
}
