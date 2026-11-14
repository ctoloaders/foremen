package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MaterialCategoryDao;
import com.foremen.dao.MaterialDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.testsupport.MockMvcSecurityConfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-11-00 task 11.2 - finishing-material regression: Project_Materials (rows with {@code project}
 * set) are invisible to the global finishing list and to the DAO queries feeding the estimate
 * picker ({@code findByActiveTrueAndRetailNetNotNull}, {@code findActiveByTypes}), while loading a
 * Project_Material by id through the global endpoint/DAO still works.
 *
 * <p>Repeatability: each test runs in a rolled-back transaction with per-run generated codes.
 *
 * <p>Validates Requirements 2.*, 19.1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class FinishingMaterialProjectScopeTest {

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
    private MaterialCategoryDao categoryDao;
    @Autowired
    private MaterialDao materialDao;
    @Autowired
    private MaterialTypeDao typeDao;
    @Autowired
    private MeasurementUnitDao unitDao;
    @Autowired
    private OfferPackageDao offerPackageDao;
    @Autowired
    private FinishingMaterialDao finishingMaterialDao;

    @PersistenceContext
    private EntityManager entityManager;

    private MaterialCategoryEntity category;
    private MaterialEntity material;
    private MaterialTypeEntity type;
    private MeasurementUnitEntity unit;
    private OfferPackageEntity offerPackage;
    private ProjectEntity project;
    private FinishingMaterialEntity globalRow;
    private FinishingMaterialEntity projectRow;

    private static String unique() {
        return System.nanoTime() + "_" + COUNTER.incrementAndGet();
    }

    @BeforeEach
    void seed() {
        String u = unique();

        category = new MaterialCategoryEntity();
        category.setCode("fps_cat_" + u);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        category = categoryDao.save(category);

        material = new MaterialEntity();
        material.setCode("fps_mat_" + u);
        material.setNameRU("Пол");
        material.setNamePL("Podłoga");
        material.setActive(true);
        material = materialDao.save(material);

        type = new MaterialTypeEntity();
        type.setCode("fps_type_" + u);
        type.setNameRU("Ламинат");
        type.setNamePL("Laminat");
        type.setActive(true);
        type = typeDao.save(type);

        unit = new MeasurementUnitEntity();
        unit.setCode("fps_unit_" + u);
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        unit = unitDao.save(unit);

        offerPackage = new OfferPackageEntity();
        offerPackage.setCode("fps_pkg_" + u);
        offerPackage.setOrderNo(1);
        offerPackage.setNameRU("Пакет");
        offerPackage.setNamePL("Pakiet");
        offerPackage.setActive(true);
        offerPackage = offerPackageDao.save(offerPackage);

        project = new ProjectEntity();
        project.setName("FPS project " + u);
        project.setStatus(ProjectStatus.ACTIVE);
        entityManager.persist(project);

        globalRow = persistMaterial(null, "Global " + u);
        projectRow = persistMaterial(project, "Project " + u);
        entityManager.flush();
        entityManager.clear();
    }

    private FinishingMaterialEntity persistMaterial(ProjectEntity owner, String model) {
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setCategory(category);
        m.setMaterial(material);
        m.setType(type);
        m.setUnit(unit);
        m.setPackages(new HashSet<>(Set.of(offerPackage)));
        m.setProject(owner);
        m.setModel(model);
        m.setRetailNet(new BigDecimal("10.00"));
        m.setCostNet(BigDecimal.ZERO);
        m.setActive(true);
        return finishingMaterialDao.save(m);
    }

    @Test
    @DisplayName("GET /api/finishing-materials - global list excludes Project_Materials")
    void globalList_excludesProjectRows() throws Exception {
        mockMvc.perform(get("/api/finishing-materials").param("page", "0").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem(globalRow.getId().intValue())))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(projectRow.getId().intValue()))));
    }

    @Test
    @DisplayName("GET /api/finishing-materials/{id} - a Project_Material still loads by id")
    void loadById_projectRow_stillWorks() throws Exception {
        mockMvc.perform(get("/api/finishing-materials/" + projectRow.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectRow.getId()));
        assertThat(finishingMaterialDao.findById(projectRow.getId())).isPresent();
    }

    @Test
    @DisplayName("DAO findByActiveTrueAndRetailNetNotNull - excludes Project_Materials")
    void daoPickerQuery_excludesProjectRows() {
        List<Long> ids = finishingMaterialDao.findByActiveTrueAndRetailNetNotNull().stream()
                .map(FinishingMaterialEntity::getId).toList();

        assertThat(ids).contains(globalRow.getId()).doesNotContain(projectRow.getId());
    }

    @Test
    @DisplayName("DAO findActiveByTypes - excludes Project_Materials")
    void daoActiveByTypes_excludesProjectRows() {
        List<Long> ids = finishingMaterialDao.findActiveByTypes(List.of(type.getId())).stream()
                .map(FinishingMaterialEntity::getId).toList();

        assertThat(ids).contains(globalRow.getId()).doesNotContain(projectRow.getId());
    }
}
