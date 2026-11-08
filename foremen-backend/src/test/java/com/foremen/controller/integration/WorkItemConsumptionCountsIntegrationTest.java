package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;

/**
 * Integration test for the synthetic per-branch consumption counts and their {@code EXISTS} filters
 * on {@code GET /api/work-items} (FOR-04-19-work-catalog-rework, task 1.4).
 *
 * <p>Covers: counts for 0/1/N norms per branch, a mixed page, the {@code >0} / {@code ==0} filters,
 * unchanged paging totals (no row duplication from the filter), invalid operator &rarr; 400, and an
 * N+1 guard via Hibernate statistics (statement count is constant for page sizes 5 and 50).
 *
 * <p>Single-item read ({@code findById}) does not carry the counts (task 1.1) and is not tested.
 *
 * <p><b>Validates: Requirements 10.3</b> (covers 2.4, 2.5, 2.6, 7.1, 7.2, 7.3, 7.4).
 *
 * <p>Repeatability: every test seeds uniquely-coded rows inside a rolled-back transaction and filters
 * the list by that unique code prefix.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class WorkItemConsumptionCountsIntegrationTest {

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
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    private static final String BASE = "/api/work-items";
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final int ITEMS = 60;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WorkItemDao workItemDao;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private ConstructionMaterialTypeDao constructionTypeDao;
    @Autowired
    private MaterialTypeDao finishingTypeDao;
    @Autowired
    private WorkMaterialConsumptionDao consumptionDao;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @PersistenceContext
    private EntityManager entityManager;

    private String unique(String prefix) {
        return prefix + System.nanoTime() + COUNTER.incrementAndGet();
    }

    /** One seeded work item with its expected counts. */
    private record Seeded(String code, long construction, long finishing) {}

    private MeasurementUnitEntity unit;
    private List<ConstructionMaterialTypeEntity> constructionTypes = new ArrayList<>();
    private List<MaterialTypeEntity> finishingTypes = new ArrayList<>();

    private void seedRefs() {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode(unique("WC"));
        category.setOrderNo(1);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        workCategoryDao.save(category);

        unit = new MeasurementUnitEntity();
        unit.setCode(unique("MU"));
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        measurementUnitDao.save(unit);

        for (int i = 0; i < 3; i++) {
            ConstructionMaterialTypeEntity t = new ConstructionMaterialTypeEntity();
            t.setCode(unique("CMT"));
            t.setNameRU("Тип" + i);
            t.setNamePL("Typ" + i);
            t.setActive(true);
            constructionTypes.add(constructionTypeDao.save(t));
        }
        for (int i = 0; i < 2; i++) {
            MaterialTypeEntity t = new MaterialTypeEntity();
            t.setCode(unique("FMT"));
            t.setNameRU("Отд" + i);
            t.setNamePL("Wyk" + i);
            t.setActive(true);
            finishingTypes.add(finishingTypeDao.save(t));
        }
        this.category = category;
    }

    private WorkCategoryEntity category;

    /**
     * Seeds {@link #ITEMS} work items {@code prefix-000..}: construction count = {@code i % 4}
     * (0..3, i.e. 0/1/N), finishing count = {@code i % 3} (0..2); i.e. a mixed population.
     */
    private List<Seeded> seed(String prefix) {
        seedRefs();
        List<Seeded> seeded = new ArrayList<>();
        for (int i = 0; i < ITEMS; i++) {
            String code = String.format("%s-%03d", prefix, i);
            WorkItemEntity item = new WorkItemEntity();
            item.setCode(code);
            item.setWorkCategory(category);
            item.setUnit(unit);
            item.setNameRU("Работа " + code);
            item.setNamePL("Praca " + code);
            item.setActive(true);
            workItemDao.save(item);

            int construction = i % 4;
            int finishing = i % 3;
            for (int j = 0; j < construction; j++) {
                consumptionDao.save(norm(item, ConsumptionBranch.construction, constructionTypes.get(j), null));
            }
            for (int j = 0; j < finishing; j++) {
                consumptionDao.save(norm(item, ConsumptionBranch.finishing, null, finishingTypes.get(j)));
            }
            seeded.add(new Seeded(code, construction, finishing));
        }
        entityManager.flush();
        entityManager.clear();
        return seeded;
    }

    private WorkMaterialConsumptionEntity norm(WorkItemEntity item, ConsumptionBranch branch,
                                               ConstructionMaterialTypeEntity cType, MaterialTypeEntity fType) {
        WorkMaterialConsumptionEntity row = new WorkMaterialConsumptionEntity();
        row.setWorkItem(item);
        row.setMaterialUnit(unit);
        row.setBranch(branch);
        row.setConstructionMaterialType(cType);
        row.setFinishingMaterialType(fType);
        row.setNormQty(new BigDecimal("1.0000"));
        row.setSourceType("excel");
        row.setSourceDoc("test");
        row.setSourceRef("row-" + COUNTER.incrementAndGet());
        return row;
    }

    private static String item(String code, String field) {
        return "$.content[?(@.code == '" + code + "')]." + field;
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Counts are 0/1/N per branch and correct on a mixed page")
    void countsPerBranchOnMixedPage() throws Exception {
        String prefix = unique("CNT");
        List<Seeded> seeded = seed(prefix);

        var result = mockMvc.perform(get(BASE)
                        .param("query", "code~sw~" + prefix)
                        .param("page", "0").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(ITEMS));
        for (Seeded s : seeded) {
            result.andExpect(jsonPath(item(s.code(), "constructionConsumptionCount")).value(
                    org.hamcrest.Matchers.contains((int) s.construction())));
            result.andExpect(jsonPath(item(s.code(), "finishingConsumptionCount")).value(
                    org.hamcrest.Matchers.contains((int) s.finishing())));
        }
        // Sanity: the population genuinely contains 0, 1 and N (>1) on both branches.
        assertThat(seeded).anyMatch(s -> s.construction() == 0).anyMatch(s -> s.construction() == 1)
                .anyMatch(s -> s.construction() > 1).anyMatch(s -> s.finishing() == 0)
                .anyMatch(s -> s.finishing() == 1).anyMatch(s -> s.finishing() > 1);
    }

    @Test
    @DisplayName(">0 / ==0 filters select exactly the rows with / without norms, totals unchanged by duplication")
    void filtersHasAndNone() throws Exception {
        String prefix = unique("FLT");
        List<Seeded> seeded = seed(prefix);
        long consHas = seeded.stream().filter(s -> s.construction() > 0).count();
        long finHas = seeded.stream().filter(s -> s.finishing() > 0).count();
        String base = "code~sw~" + prefix;

        mockMvc.perform(get(BASE).param("query", base + " AND constructionConsumptionCount>0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(consHas))
                .andExpect(jsonPath("$.content.length()").value((int) consHas))
                .andExpect(jsonPath("$.content[?(@.constructionConsumptionCount == 0)]").doesNotExist());

        mockMvc.perform(get(BASE).param("query", base + " AND constructionConsumptionCount==0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(ITEMS - consHas))
                .andExpect(jsonPath("$.content[?(@.constructionConsumptionCount > 0)]").doesNotExist());

        mockMvc.perform(get(BASE).param("query", base + " AND finishingConsumptionCount>0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(finHas))
                .andExpect(jsonPath("$.content[?(@.finishingConsumptionCount == 0)]").doesNotExist());

        mockMvc.perform(get(BASE).param("query", base + " AND finishingConsumptionCount==0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(ITEMS - finHas))
                .andExpect(jsonPath("$.content[?(@.finishingConsumptionCount > 0)]").doesNotExist());

        // Both filters combined: intersection, still one row per work item (no duplication).
        long both = seeded.stream().filter(s -> s.construction() > 0 && s.finishing() > 0).count();
        mockMvc.perform(get(BASE).param("query",
                                base + " AND constructionConsumptionCount>0 AND finishingConsumptionCount>0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(both))
                .andExpect(jsonPath("$.content.length()").value((int) both));
    }

    @Test
    @DisplayName("Paging totals are unchanged: totalElements is independent of page size and of per-item norm count")
    void pagingTotalsUnchanged() throws Exception {
        String prefix = unique("PGT");
        seed(prefix);

        mockMvc.perform(get(BASE).param("query", "code~sw~" + prefix).param("page", "0").param("size", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(ITEMS))
                .andExpect(jsonPath("$.content.length()").value(7));
        mockMvc.perform(get(BASE).param("query", "code~sw~" + prefix).param("page", "8").param("size", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(ITEMS))
                .andExpect(jsonPath("$.content.length()").value(ITEMS - 56));
    }

    @Test
    @DisplayName("Unsupported operator/value on a count segment -> 400")
    void invalidOperatorIs400() throws Exception {
        String prefix = unique("BAD");
        seed(prefix);

        mockMvc.perform(get(BASE).param("query", "code~sw~" + prefix + " AND constructionConsumptionCount>5"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE).param("query", "code~sw~" + prefix + " AND finishingConsumptionCount~ct~1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE).param("query", "code~sw~" + prefix + " AND finishingConsumptionCount>abc"))
                .andExpect(status().isBadRequest());
    }

    /**
     * N+1 guard for the consumption counts: for page sizes 5 and 50 the grouped count query runs
     * exactly once per list request, so the statements contributed by the counts are constant in page
     * size.
     *
     * <p>Note: the TOTAL prepared-statement count of the list is NOT constant today because the
     * pre-existing {@code WorkItemServiceMapper.resolveCostCell} resolves the {@code costCell} per row
     * (and loads consumption rows for it), outside this spec's scope. The guard therefore targets the
     * count path specifically.
     */
    @Test
    @DisplayName("N+1 guard: consumption-count statements are constant for page sizes 5 and 50")
    void countStatementsAreConstantAcrossPageSizes() throws Exception {
        String prefix = unique("N1");
        seed(prefix);
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(stats.isStatisticsEnabled()).as("hibernate statistics enabled").isTrue();

        // Warm-up (query plan caches, lazy metadata) so measured runs are comparable.
        list(prefix, 50);

        Measured small = measure(prefix, 5);
        Measured large = measure(prefix, 50);

        assertThat(small.countQueryExecutions()).as("grouped count query runs once for size=5").isEqualTo(1L);
        assertThat(large.countQueryExecutions()).as("grouped count query runs once for size=50").isEqualTo(1L);
    }

    private record Measured(long countQueryExecutions) {}

    private Measured measure(String prefix, int size) throws Exception {
        entityManager.clear();
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        list(prefix, size);
        long countExecutions = 0;
        for (String q : stats.getQueries()) {
            if (q.contains("GROUP BY c.workItem.id, c.branch")) {
                countExecutions += stats.getQueryStatistics(q).getExecutionCount();
            }
        }
        return new Measured(countExecutions);
    }

    private void list(String prefix, int size) throws Exception {
        entityManager.clear();
        mockMvc.perform(get(BASE).param("query", "code~sw~" + prefix).param("page", "0")
                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(size));
    }
}
