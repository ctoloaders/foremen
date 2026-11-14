package com.foremen.service.procurement.planner;

import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test of {@link WorkConsumptionLookup} (finishing branch only, tier 2 over the catalog).
 *
 * <p>Repeatability: every fixture code carries a per-run id and {@link #cleanUp()} removes the rows.
 *
 * <p><b>Validates: Requirements 5.2, 5.3</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class WorkConsumptionLookupIntegrationTest {

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

    @Autowired private WorkConsumptionLookup lookup;
    @Autowired private WorkCategoryDao workCategoryDao;
    @Autowired private WorkItemDao workItemDao;
    @Autowired private MaterialTypeDao materialTypeDao;
    @Autowired private MeasurementUnitDao measurementUnitDao;
    @Autowired private WorkMaterialConsumptionDao consumptionDao;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        tx().executeWithoutResult(s -> {
            entityManager.createQuery("delete from WorkMaterialConsumptionEntity c where c.workItem.id in "
                            + "(select wi.id from WorkItemEntity wi where wi.code like :p)")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery("delete from WorkItemEntity wi where wi.code like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery("delete from WorkCategoryEntity wc where wc.code like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery("delete from MaterialTypeEntity t where t.code like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery("delete from MeasurementUnitEntity u where u.code like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
        });
    }

    private record Fixture(Long catA, Long catB, Long workA, Long workB, Long type1, Long type2) {
    }

    /**
     * Type1 is consumed by a work of category A and a work of category B; type2 only by the work of
     * category B. A finishing row exists for each pair.
     */
    private Fixture createFixture() {
        return tx().execute(s -> {
            MeasurementUnitEntity unit = new MeasurementUnitEntity();
            unit.setCode("U_" + runId);
            unit.setNameRU("ед " + runId);
            unit.setNamePL("jedn " + runId);
            unit.setActive(true);
            unit = measurementUnitDao.save(unit);

            WorkCategoryEntity a = category("A", 1);
            WorkCategoryEntity b = category("B", 2);
            WorkItemEntity wa = workItem("WA", a, unit);
            WorkItemEntity wb = workItem("WB", b, unit);
            MaterialTypeEntity t1 = type("T1");
            MaterialTypeEntity t2 = type("T2");
            consume(wa, t1, unit);
            consume(wb, t1, unit);
            consume(wb, t2, unit);
            return new Fixture(a.getId(), b.getId(), wa.getId(), wb.getId(), t1.getId(), t2.getId());
        });
    }

    @Test
    void tier1_returnsOnlyCategoriesOfTheGivenWorkItems() {
        Fixture f = createFixture();

        Map<Long, Set<Long>> result = lookup.categoriesByTypeForWorkItems(List.of(f.workA()));

        assertThat(result).containsOnlyKeys(f.type1());
        assertThat(result.get(f.type1())).containsExactly(f.catA());
    }

    @Test
    void tier2_returnsCatalogWideCategoriesForRequestedTypesOnly() {
        Fixture f = createFixture();

        Map<Long, Set<Long>> result = lookup.catalogCategoriesByType(List.of(f.type1(), f.type2()));

        assertThat(result.get(f.type1())).containsExactlyInAnyOrder(f.catA(), f.catB());
        assertThat(result.get(f.type2())).containsExactly(f.catB());

        assertThat(lookup.catalogCategoriesByType(List.of(f.type2())))
                .containsOnlyKeys(f.type2());
    }

    @Test
    void emptyInputsGiveEmptyMaps() {
        assertThat(lookup.categoriesByTypeForWorkItems(List.of())).isEmpty();
        assertThat(lookup.catalogCategoriesByType(List.of())).isEmpty();
        assertThat(lookup.estimateCategoriesByType(null)).isEmpty();
    }

    private WorkCategoryEntity category(String key, int order) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setCode("CAT_" + key + "_" + runId);
        c.setOrderNo(order);
        c.setNameRU("Кат " + key);
        c.setNamePL("Kat " + key);
        c.setActive(true);
        return workCategoryDao.save(c);
    }

    private WorkItemEntity workItem(String key, WorkCategoryEntity cat, MeasurementUnitEntity unit) {
        WorkItemEntity w = new WorkItemEntity();
        w.setWorkCategory(cat);
        w.setUnit(unit);
        w.setCode(key + "_" + runId);
        w.setNameRU("Работа " + key);
        w.setNamePL("Praca " + key);
        w.setActive(true);
        return workItemDao.save(w);
    }

    private MaterialTypeEntity type(String key) {
        MaterialTypeEntity t = new MaterialTypeEntity();
        t.setCode(key + "_" + runId);
        t.setNameRU("Тип " + key);
        t.setNamePL("Typ " + key);
        t.setActive(true);
        return materialTypeDao.save(t);
    }

    private void consume(WorkItemEntity work, MaterialTypeEntity type, MeasurementUnitEntity unit) {
        WorkMaterialConsumptionEntity c = new WorkMaterialConsumptionEntity();
        c.setWorkItem(work);
        c.setMaterialUnit(unit);
        c.setBranch(ConsumptionBranch.finishing);
        c.setFinishingMaterialType(type);
        c.setNormQty(new BigDecimal("1.0000"));
        c.setConsumptionBasis(ConsumptionBasis.PER_UNIT);
        c.setSourceType("MANUAL");
        c.setSourceDoc("test");
        c.setSourceRef("test");
        consumptionDao.save(c);
    }
}
