package com.foremen.controller.integration;

import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkPackageOverrideDao;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
 * Integration tests for the work-catalog list's {@code packages} column and package filter
 * (W2B #9). A work item "belongs to" an offer package when a
 * {@code WorkPackageOverride(workItem, offerPackage, member=true)} row exists — membership is NOT a
 * plain M:N, so the {@code packages} list is batch-populated by {@code WorkItemService} and the
 * {@code packages.id} filter is an EXISTS subquery registered by {@code WorkItemPackagesQueryResolver}.
 *
 * <p>Verifies (against a Testcontainers PostgreSQL, same boot/auth/rollback harness as
 * {@link WorkItemControllerIntegrationTest}):
 * <ul>
 *   <li>a member work item exposes the package in its {@code packages} list (id + localized name);</li>
 *   <li>a {@code packages.id==<id>} filter returns members of that package and excludes non-members;</li>
 *   <li>a {@code packages.id~in~<a>,<b>} multi-select returns the union, a work in both appearing once;</li>
 *   <li>an override row with {@code member=false} is NOT treated as membership.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class WorkItemPackagesFilterIntegrationTest {

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
    private WorkItemDao workItemDao;

    @Autowired
    private WorkCategoryDao workCategoryDao;

    @Autowired
    private MeasurementUnitDao measurementUnitDao;

    @Autowired
    private OfferPackageDao offerPackageDao;

    @Autowired
    private WorkPackageOverrideDao workPackageOverrideDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String unique(String prefix) {
        return prefix + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private WorkCategoryEntity createCategory() {
        WorkCategoryEntity entity = new WorkCategoryEntity();
        entity.setCode(unique("WC"));
        entity.setOrderNo(1);
        entity.setNameRU("Категория");
        entity.setNamePL("Kategoria");
        entity.setActive(true);
        return workCategoryDao.save(entity);
    }

    private MeasurementUnitEntity createUnit() {
        MeasurementUnitEntity entity = new MeasurementUnitEntity();
        entity.setCode(unique("MU"));
        entity.setNameRU("шт");
        entity.setNamePL("szt");
        entity.setActive(true);
        return measurementUnitDao.save(entity);
    }

    private OfferPackageEntity createPackage(int orderNo, String nameRU, String namePL) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode(unique("PKG"));
        pkg.setOrderNo(orderNo);
        pkg.setNameRU(nameRU);
        pkg.setNamePL(namePL);
        pkg.setActive(true);
        return offerPackageDao.save(pkg);
    }

    private WorkItemEntity createWorkItem(WorkCategoryEntity category, MeasurementUnitEntity unit,
                                          String nameRU, String namePL) {
        WorkItemEntity entity = new WorkItemEntity();
        entity.setWorkCategory(category);
        entity.setUnit(unit);
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);
        entity.setActive(true);
        return workItemDao.save(entity);
    }

    private void addMembership(WorkItemEntity work, OfferPackageEntity pkg, boolean member) {
        WorkPackageOverrideEntity override = new WorkPackageOverrideEntity();
        override.setWorkItem(work);
        override.setOfferPackage(pkg);
        override.setMember(member);
        workPackageOverrideDao.save(override);
    }

    @Test
    @DisplayName("GET /api/work-items - a member work exposes the package in its packages list (id + PL name)")
    void listExposesPackagesForMemberWork() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity unit = createUnit();
        OfferPackageEntity pkg = createPackage(1, "Пакет A", "Pakiet A");
        WorkItemEntity work = createWorkItem(category, unit, "Работа", "Praca");
        addMembership(work, pkg, true);
        entityManager.flush();

        mockMvc.perform(get("/api/work-items")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("query", "id==" + work.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + work.getId() + ")].packages[?(@.id == "
                        + pkg.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + work.getId() + ")].packages[?(@.id == "
                        + pkg.getId() + ")].name").value(contains("Pakiet A")));
    }

    @Test
    @DisplayName("GET /api/work-items?query=packages.id==<id> - returns members, excludes non-members")
    void filterByPackageId_singleSelect() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity unit = createUnit();
        OfferPackageEntity pkgA = createPackage(1, "Пакет A", "Pakiet A");
        OfferPackageEntity pkgB = createPackage(2, "Пакет B", "Pakiet B");

        WorkItemEntity memberOfA = createWorkItem(category, unit, "Член A", "Czlonek A");
        WorkItemEntity memberOfB = createWorkItem(category, unit, "Член B", "Czlonek B");
        addMembership(memberOfA, pkgA, true);
        addMembership(memberOfB, pkgB, true);
        entityManager.flush();

        mockMvc.perform(get("/api/work-items")
                        .param("query", "packages.id==" + pkgA.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + memberOfA.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + memberOfB.getId() + ")]").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/work-items?query=packages.id~in~<a>,<b> - multi-select returns the union, deduplicated")
    void filterByPackageId_multiSelect() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity unit = createUnit();
        OfferPackageEntity pkgA = createPackage(1, "Пакет A", "Pakiet A");
        OfferPackageEntity pkgB = createPackage(2, "Пакет B", "Pakiet B");
        OfferPackageEntity pkgC = createPackage(3, "Пакет C", "Pakiet C");

        WorkItemEntity inA = createWorkItem(category, unit, "В A", "W A");
        WorkItemEntity inB = createWorkItem(category, unit, "В B", "W B");
        WorkItemEntity inBoth = createWorkItem(category, unit, "В обоих", "W obu");
        WorkItemEntity inCOnly = createWorkItem(category, unit, "Только C", "Tylko C");
        addMembership(inA, pkgA, true);
        addMembership(inB, pkgB, true);
        addMembership(inBoth, pkgA, true);
        addMembership(inBoth, pkgB, true);
        addMembership(inCOnly, pkgC, true);
        entityManager.flush();

        mockMvc.perform(get("/api/work-items")
                        .param("query", "packages.id~in~" + pkgA.getId() + "," + pkgB.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + inA.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + inB.getId() + ")]").exists())
                // inBoth matches via two packages but must appear exactly once (EXISTS, not join fan-out).
                .andExpect(jsonPath("$.content[?(@.id == " + inBoth.getId() + ")]", hasSize(1)))
                .andExpect(jsonPath("$.content[?(@.id == " + inCOnly.getId() + ")]").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/work-items - a member=false override is NOT treated as membership")
    void nonMemberOverrideIsNotMembership() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity unit = createUnit();
        OfferPackageEntity pkg = createPackage(1, "Пакет A", "Pakiet A");
        WorkItemEntity work = createWorkItem(category, unit, "Не член", "Nie czlonek");
        addMembership(work, pkg, false);
        entityManager.flush();

        // Not returned by the package filter.
        mockMvc.perform(get("/api/work-items")
                        .param("query", "packages.id==" + pkg.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + work.getId() + ")]").doesNotExist());

        // And its packages list is empty on read.
        mockMvc.perform(get("/api/work-items")
                        .param("query", "id==" + work.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + work.getId() + ")].packages")
                        .value(contains(empty())));
    }
}
