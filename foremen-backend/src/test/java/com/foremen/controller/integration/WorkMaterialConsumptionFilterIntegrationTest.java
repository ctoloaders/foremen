package com.foremen.controller.integration;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration test for the FOR-04-19 work-catalog material-consumption <b>reference filters</b>
 * (task 12.2, Requirements 3.7, 10.2).
 *
 * <p>The consumption list at {@code GET /api/work-material-consumptions} carries no bespoke filter
 * endpoint — reference filters ride the generic FOR-04-01 query DSL over the
 * {@code SpecificationBuilder} grammar (dotted reference paths {@code <ref>.id==<id>} and the scalar
 * enum path {@code branch==<value>}). This test seeds one small deterministic dataset spanning two
 * work items, two offer packages, two material units, a construction analog type and a finishing
 * analog type ({@link MaterialTypeEntity}), and both branches, then — for EACH filter — issues the
 * filtered GET and asserts ONLY the matching rows come back and every non-matching row is excluded:
 * <ul>
 *   <li>{@code workItem.id==<id>} (Requirement 3.7)</li>
 *   <li>{@code offerPackage.id==<id>} (Requirement 3.7)</li>
 *   <li>{@code materialUnit.id==<id>} (Requirement 3.7)</li>
 *   <li>{@code constructionMaterialType.id==<id>} — the construction-branch analog group (3.7)</li>
 *   <li>{@code finishingMaterialType.id==<id>} — the finishing-branch analog group (3.7)</li>
 *   <li>{@code branch==construction} and {@code branch==finishing} — the scalar enum path (3.7)</li>
 * </ul>
 *
 * <p><b>Harness.</b> Mirrors {@link WorkMaterialConsumptionDrillInIntegrationTest}:
 * {@code @SpringBootTest} + MockMvc against a Testcontainers PostgreSQL, {@code create-drop} DDL (so
 * Liquibase is off and this test owns its schema), {@code @WithMockUser(roles = "ADMIN")} so the
 * {@code WORK_MATERIAL_CONSUMPTION}/{@code READ} gate is satisfied, and {@code @Transactional}
 * rollback so each seeded dataset is isolated. The finishing branch references a plain
 * {@link MaterialTypeEntity} directly (no priced analog batch is needed — the filter tests exercise
 * the WHERE clause, not the read-time money-range computation).
 *
 * <p>Validates: Requirements 3.7 (reference + branch filters return only matching rows), 10.2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class WorkMaterialConsumptionFilterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private static final String BASE = "/api/work-material-consumptions";

    /** Per-run unique suffix so seeded reference codes never collide across tests / runs. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkMaterialConsumptionDao consumptionDao;

    @Autowired
    private WorkItemDao workItemDao;

    @Autowired
    private WorkCategoryDao workCategoryDao;

    @Autowired
    private OfferPackageDao offerPackageDao;

    @Autowired
    private MeasurementUnitDao measurementUnitDao;

    @Autowired
    private ConstructionMaterialTypeDao constructionMaterialTypeDao;

    @Autowired
    private MaterialTypeDao materialTypeDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String unique(String prefix) {
        return prefix + System.nanoTime() + COUNTER.incrementAndGet();
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures (reuse the drill-in harness's builders)
    // ---------------------------------------------------------------------------------------------

    private WorkCategoryEntity createCategory() {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode(unique("WC"));
        category.setOrderNo(1);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        return workCategoryDao.save(category);
    }

    private MeasurementUnitEntity createUnit(String nameRU, String namePL) {
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode(unique("MU"));
        unit.setNameRU(nameRU);
        unit.setNamePL(namePL);
        unit.setActive(true);
        return measurementUnitDao.save(unit);
    }

    private OfferPackageEntity createPackage(int orderNo, String nameRU, String namePL) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode(unique("OP"));
        pkg.setOrderNo(orderNo);
        pkg.setNameRU(nameRU);
        pkg.setNamePL(namePL);
        pkg.setActive(true);
        return offerPackageDao.save(pkg);
    }

    private WorkItemEntity createWorkItem(String code, WorkCategoryEntity category,
                                          MeasurementUnitEntity unit, String nameRU, String namePL) {
        WorkItemEntity item = new WorkItemEntity();
        item.setCode(code);
        item.setWorkCategory(category);
        item.setUnit(unit);
        item.setNameRU(nameRU);
        item.setNamePL(namePL);
        item.setActive(true);
        return workItemDao.save(item);
    }

    private ConstructionMaterialTypeEntity createConstructionType(String nameRU, String namePL) {
        ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
        type.setCode(unique("CMT"));
        type.setNameRU(nameRU);
        type.setNamePL(namePL);
        type.setActive(true);
        return constructionMaterialTypeDao.save(type);
    }

    private MaterialTypeEntity createFinishingType(String nameRU, String namePL) {
        MaterialTypeEntity type = new MaterialTypeEntity();
        type.setCode(unique("MT"));
        type.setNameRU(nameRU);
        type.setNamePL(namePL);
        type.setActive(true);
        return materialTypeDao.save(type);
    }

    /** A construction-branch consumption row referencing {@code constructionMaterialType}. */
    private WorkMaterialConsumptionEntity createConstructionConsumption(
            WorkItemEntity workItem, OfferPackageEntity offerPackage, MeasurementUnitEntity materialUnit,
            ConstructionMaterialTypeEntity type, BigDecimal normQty) {
        WorkMaterialConsumptionEntity row = new WorkMaterialConsumptionEntity();
        row.setWorkItem(workItem);
        row.setMaterialUnit(materialUnit);
        row.setBranch(ConsumptionBranch.construction);
        row.setConstructionMaterialType(type);
        row.setNormQty(normQty);
        row.setSourceType("excel");
        row.setSourceDoc("Matrix Foremen v3.0");
        row.setSourceRef("row-" + COUNTER.incrementAndGet());
        return consumptionDao.save(row);
    }

    /** A finishing-branch consumption row referencing {@code finishingMaterialType}. */
    private WorkMaterialConsumptionEntity createFinishingConsumption(
            WorkItemEntity workItem, OfferPackageEntity offerPackage, MeasurementUnitEntity materialUnit,
            MaterialTypeEntity type, BigDecimal normQty) {
        WorkMaterialConsumptionEntity row = new WorkMaterialConsumptionEntity();
        row.setWorkItem(workItem);
        row.setMaterialUnit(materialUnit);
        row.setBranch(ConsumptionBranch.finishing);
        row.setFinishingMaterialType(type);
        row.setNormQty(normQty);
        row.setSourceType("excel");
        row.setSourceDoc("Matrix Foremen v3.0");
        row.setSourceRef("row-" + COUNTER.incrementAndGet());
        return consumptionDao.save(row);
    }

    // ---------------------------------------------------------------------------------------------
    // Deterministic seed shared by every filter case.
    //
    // 2 works (workA, workB) × 2 packages (budget, lux) × 2 units (kg, l), a construction type
    // (glue) and a finishing type (paint), spanning both branches:
    //
    //   r1: workA, budget, kg, construction/glue     ← construction, workA, budget, kg, glue
    //   r2: workA, lux,    l,  construction/glue      ← construction, workA, lux,    l,  glue
    //   r3: workB, budget, kg, construction/glue      ← construction, workB, budget, kg, glue
    //   r4: workA, budget, l,  finishing/paint        ← finishing,    workA, budget, l,  paint
    //   r5: workB, lux,    kg, finishing/paint         ← finishing,    workB, lux,    kg, paint
    // ---------------------------------------------------------------------------------------------

    private static final class Seed {
        WorkItemEntity workA;
        WorkItemEntity workB;
        OfferPackageEntity budget;
        OfferPackageEntity lux;
        MeasurementUnitEntity kg;
        MeasurementUnitEntity litre;
        ConstructionMaterialTypeEntity glue;
        MaterialTypeEntity paint;
        WorkMaterialConsumptionEntity r1;
        WorkMaterialConsumptionEntity r2;
        WorkMaterialConsumptionEntity r3;
        WorkMaterialConsumptionEntity r4;
        WorkMaterialConsumptionEntity r5;
    }

    private Seed seed() {
        Seed s = new Seed();
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity workUnit = createUnit("м2", "m2");
        s.kg = createUnit("кг", "kg");
        s.litre = createUnit("л", "l");
        s.budget = createPackage(1, "Бюджет", "Budżet");
        s.lux = createPackage(3, "Люкс", "Lux");
        s.workA = createWorkItem("1.01", category, workUnit, "Работа 1.01", "Praca 1.01");
        s.workB = createWorkItem("2.01", category, workUnit, "Работа 2.01", "Praca 2.01");
        s.glue = createConstructionType("Клей", "Klej");
        s.paint = createFinishingType("Краска", "Farba");

        s.r1 = createConstructionConsumption(s.workA, s.budget, s.kg, s.glue, new BigDecimal("5.0000"));
        s.r2 = createConstructionConsumption(s.workA, s.lux, s.litre, s.glue, new BigDecimal("6.0000"));
        s.r3 = createConstructionConsumption(s.workB, s.budget, s.kg, s.glue, new BigDecimal("7.0000"));
        s.r4 = createFinishingConsumption(s.workA, s.budget, s.litre, s.paint, new BigDecimal("8.0000"));
        s.r5 = createFinishingConsumption(s.workB, s.lux, s.kg, s.paint, new BigDecimal("9.0000"));

        entityManager.flush();
        entityManager.clear();
        return s;
    }

    // ---------------------------------------------------------------------------------------------
    // Filter cases (Requirement 3.7 / 10.2): each returns ONLY the matching rows.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Filter workItem.id== returns only that work item's rows and excludes the other work")
    void filterByWorkItemId_returnsOnlyMatchingRows() throws Exception {
        Seed s = seed();

        // workA rows: r1, r2, r4. workB rows: r3, r5 (excluded).
        mockMvc.perform(get(BASE)
                        .param("query", "workItem.id==" + s.workA.getId())
                        .param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.id == " + s.r1.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r2.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r4.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r3.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r5.getId() + ")]").doesNotExist());
    }

    @Test
    @DisplayName("Filter offerPackage.id== returns only that package's rows and excludes the other package")
    void filterByOfferPackageId_returnsOnlyMatchingRows() throws Exception {
        Seed s = seed();

        // budget rows: r1, r3, r4. lux rows: r2, r5 (excluded).
        mockMvc.perform(get(BASE)
                        .param("query", "offerPackage.id==" + s.budget.getId())
                        .param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.id == " + s.r1.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r3.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r4.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r2.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r5.getId() + ")]").doesNotExist());
    }

    @Test
    @DisplayName("Filter materialUnit.id== returns only rows with that numerator unit and excludes the other unit")
    void filterByMaterialUnitId_returnsOnlyMatchingRows() throws Exception {
        Seed s = seed();

        // kg rows: r1, r3, r5. litre rows: r2, r4 (excluded).
        mockMvc.perform(get(BASE)
                        .param("query", "materialUnit.id==" + s.kg.getId())
                        .param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.id == " + s.r1.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r3.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r5.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r2.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r4.getId() + ")]").doesNotExist());
    }

    @Test
    @DisplayName("Filter constructionMaterialType.id== returns only the construction-branch rows of that type")
    void filterByConstructionMaterialTypeId_returnsOnlyMatchingRows() throws Exception {
        Seed s = seed();

        // glue (construction) rows: r1, r2, r3. Finishing rows r4, r5 have a NULL
        // construction_material_type_id and must be excluded.
        mockMvc.perform(get(BASE)
                        .param("query", "constructionMaterialType.id==" + s.glue.getId())
                        .param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.id == " + s.r1.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r2.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r3.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r4.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r5.getId() + ")]").doesNotExist());
    }

    @Test
    @DisplayName("Filter finishingMaterialType.id== returns only the finishing-branch rows of that type")
    void filterByFinishingMaterialTypeId_returnsOnlyMatchingRows() throws Exception {
        Seed s = seed();

        // paint (finishing) rows: r4, r5. Construction rows r1, r2, r3 have a NULL
        // finishing_material_type_id and must be excluded.
        mockMvc.perform(get(BASE)
                        .param("query", "finishingMaterialType.id==" + s.paint.getId())
                        .param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.id == " + s.r4.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r5.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r1.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r2.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r3.getId() + ")]").doesNotExist());
    }

    @Test
    @DisplayName("Filter branch==construction returns only construction rows; branch==finishing only finishing rows")
    void filterByBranch_returnsOnlyMatchingRows() throws Exception {
        Seed s = seed();

        // construction rows: r1, r2, r3. finishing rows r4, r5 excluded.
        mockMvc.perform(get(BASE)
                        .param("query", "branch==construction")
                        .param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.id == " + s.r1.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r2.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r3.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r4.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r5.getId() + ")]").doesNotExist());

        // finishing rows: r4, r5. construction rows r1, r2, r3 excluded.
        mockMvc.perform(get(BASE)
                        .param("query", "branch==finishing")
                        .param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.id == " + s.r4.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r5.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r1.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r2.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == " + s.r3.getId() + ")]").doesNotExist());
    }
}
