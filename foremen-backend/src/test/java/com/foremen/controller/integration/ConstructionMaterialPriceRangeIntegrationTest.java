package com.foremen.controller.integration;

import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for the COMPUTED price range (FOR-04-17, Requirement 6), the
 * example-based sibling of {@code PriceRangeResolverPropertyTest} (Property 1, task 7.3).
 *
 * <p>Mirrors the {@code @SpringBootTest} + MockMvc + Testcontainers boot pattern of
 * {@link ConstructionMaterialTypeControllerIntegrationTest}: a Testcontainers PostgreSQL with
 * {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")} and {@code @Transactional} rollback
 * so each test seeds its own deterministic dataset without cross-test contamination.
 *
 * <p>Seeds one small deterministic catalog — one type, three offer packages, and a handful of
 * construction materials with known {@code retailNet} + package memberships (plus a null-priced and
 * an inactive material) — then asserts the two read surfaces that expose the range:
 * <ul>
 *   <li>{@code GET /api/construction-materials/price-ranges} (full map and single-pair form);</li>
 *   <li>the per-row {@code priceRanges} payload on the list DTO ({@code GET /api/construction-materials}).</li>
 * </ul>
 * It verifies MIN/MAX per {@code (package, type)} pair, that a multi-package material contributes to
 * EACH of its packages, that a null-{@code retailNet} (and an inactive) material is excluded, and
 * that an unpriced pair yields an empty range (null min/max).
 *
 * <p>Validates Requirement 12.4 (and, by example, 6.1–6.7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class ConstructionMaterialPriceRangeIntegrationTest {

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

    /** Per-run unique code suffix so the seed is repeatable even without rollback. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConstructionMaterialDao constructionMaterialDao;

    @Autowired
    private ConstructionMaterialTypeDao typeDao;

    @Autowired
    private OfferPackageDao offerPackageDao;

    @Autowired
    private MeasurementUnitDao measurementUnitDao;

    @Autowired
    private CurrencyDao currencyDao;

    @PersistenceContext
    private EntityManager entityManager;

    // --- shared deterministic dataset, seeded per-test ---

    private ConstructionMaterialTypeEntity type;
    private OfferPackageEntity p1;
    private OfferPackageEntity p2;
    private OfferPackageEntity p3;
    private MeasurementUnitEntity unit;
    private CurrencyEntity currency;

    private String uniqueCode() {
        return "prtest" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private ConstructionMaterialTypeEntity createType() {
        ConstructionMaterialTypeEntity t = new ConstructionMaterialTypeEntity();
        t.setCode(uniqueCode());
        t.setNameRU("Краска");
        t.setNamePL("Farba");
        t.setActive(true);
        return typeDao.save(t);
    }

    private OfferPackageEntity createPackage(int orderNo) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode(uniqueCode());
        pkg.setOrderNo(orderNo);
        pkg.setNameRU("Пакет " + orderNo);
        pkg.setNamePL("Pakiet " + orderNo);
        pkg.setActive(true);
        return offerPackageDao.save(pkg);
    }

    private MeasurementUnitEntity createUnit() {
        MeasurementUnitEntity u = new MeasurementUnitEntity();
        u.setCode(uniqueCode());
        u.setNameRU("шт");
        u.setNamePL("szt");
        u.setActive(true);
        return measurementUnitDao.save(u);
    }

    private CurrencyEntity createCurrency() {
        CurrencyEntity c = new CurrencyEntity();
        c.setCode(uniqueCode().substring(0, 8));
        c.setSymbol("zł");
        c.setNameRU("Злотый");
        c.setNamePL("Złoty");
        c.setActive(true);
        return currencyDao.save(c);
    }

    private ConstructionMaterialEntity createMaterial(
            ConstructionMaterialTypeEntity materialType,
            BigDecimal retailNet,
            boolean active,
            OfferPackageEntity... packages) {
        ConstructionMaterialEntity material = new ConstructionMaterialEntity();
        material.setNameRU("Материал");
        material.setNamePL("Materiał");
        material.setType(materialType);
        material.setUnit(unit);
        material.setCurrency(currency);
        material.setRetailNet(retailNet);
        material.setActive(active);
        material.setPackages(Set.of(packages));
        return constructionMaterialDao.save(material);
    }

    @BeforeEach
    void seed() {
        type = createType();
        p1 = createPackage(1);
        p2 = createPackage(2);
        p3 = createPackage(3);
        unit = createUnit();
        currency = createCurrency();

        // (P1, T): M1=10, M2=30, M3=20  → min 10, max 30
        // (P2, T): only M3=20            → min 20, max 20 (multi-package fan-out)
        // (P3, T): no priced active mat  → empty range
        createMaterial(type, new BigDecimal("10.00"), true, p1);           // M1
        createMaterial(type, new BigDecimal("30.00"), true, p1);           // M2
        createMaterial(type, new BigDecimal("20.00"), true, p1, p2);       // M3 (multi-package)
        createMaterial(type, null, true, p1);                              // M4 null retailNet → excluded
        createMaterial(type, new BigDecimal("5.00"), false, p1, p3);       // M5 inactive → excluded (guards P3 stays empty)

        entityManager.flush();
        entityManager.clear();
    }

    // --- GET /price-ranges?packageId=&typeId= (single pair) ---

    @Test
    @DisplayName("GET /price-ranges (P1,T) - MIN/MAX across all qualifying materials of the pair")
    void singlePair_minMaxAcrossQualifyingMaterials() throws Exception {
        mockMvc.perform(get("/api/construction-materials/price-ranges")
                        .param("packageId", String.valueOf(p1.getId()))
                        .param("typeId", String.valueOf(type.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.min").value(10.00))
                .andExpect(jsonPath("$.max").value(30.00));
    }

    @Test
    @DisplayName("GET /price-ranges (P2,T) - a multi-package material contributes to its second package")
    void singlePair_multiPackageMaterialContributesToEachPackage() throws Exception {
        // Only M3 (retailNet 20, packages {P1, P2}) reaches P2, so the pair is exactly 20..20.
        mockMvc.perform(get("/api/construction-materials/price-ranges")
                        .param("packageId", String.valueOf(p2.getId()))
                        .param("typeId", String.valueOf(type.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.min").value(20.00))
                .andExpect(jsonPath("$.max").value(20.00));
    }

    @Test
    @DisplayName("GET /price-ranges (P3,T) - an unpriced pair yields an empty range (null min/max)")
    void singlePair_unpricedPair_returnsEmptyRange() throws Exception {
        // P3 is only referenced by the inactive M5, which is excluded → empty range.
        mockMvc.perform(get("/api/construction-materials/price-ranges")
                        .param("packageId", String.valueOf(p3.getId()))
                        .param("typeId", String.valueOf(type.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.min").doesNotExist())
                .andExpect(jsonPath("$.max").doesNotExist());
    }

    // --- GET /price-ranges (full map) ---

    @Test
    @DisplayName("GET /price-ranges (full map) - one entry per priced pair with the right MIN/MAX")
    void fullMap_containsEntryPerPricedPairWithMinMax() throws Exception {
        String byP1 = "$[?(@.offerPackageId == " + p1.getId()
                + " && @.constructionMaterialTypeId == " + type.getId() + ")]";
        String byP2 = "$[?(@.offerPackageId == " + p2.getId()
                + " && @.constructionMaterialTypeId == " + type.getId() + ")]";
        String byP3 = "$[?(@.offerPackageId == " + p3.getId()
                + " && @.constructionMaterialTypeId == " + type.getId() + ")]";

        mockMvc.perform(get("/api/construction-materials/price-ranges"))
                .andExpect(status().isOk())
                // (P1, T): 10..30
                .andExpect(jsonPath(byP1 + ".min").value(contains(10.00)))
                .andExpect(jsonPath(byP1 + ".max").value(contains(30.00)))
                // (P2, T): 20..20 (multi-package fan-out)
                .andExpect(jsonPath(byP2 + ".min").value(contains(20.00)))
                .andExpect(jsonPath(byP2 + ".max").value(contains(20.00)))
                // (P3, T): no qualifying material → the pair is absent from the map entirely
                .andExpect(jsonPath(byP3).doesNotExist());
    }

    // --- per-row priceRanges on the list DTO ---

    @Test
    @DisplayName("GET /api/construction-materials - each row's priceRanges carries its own (package,type) ranges")
    void listRow_priceRangesPayloadPerPackage() throws Exception {
        // The multi-package M3 (retailNet 20, packages {P1, P2}) is present on the list and its
        // priceRanges payload must carry BOTH its packages: (P1,T)=10..30 and (P2,T)=20..20.
        String m3 = "$.content[?(@.retailNet == 20.00)]";
        String m3P1 = m3 + ".priceRanges[?(@.offerPackageId == " + p1.getId() + ")]";
        String m3P2 = m3 + ".priceRanges[?(@.offerPackageId == " + p2.getId() + ")]";

        mockMvc.perform(get("/api/construction-materials")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(m3).exists())
                .andExpect(jsonPath(m3P1 + ".min").value(contains(10.00)))
                .andExpect(jsonPath(m3P1 + ".max").value(contains(30.00)))
                .andExpect(jsonPath(m3P2 + ".min").value(contains(20.00)))
                .andExpect(jsonPath(m3P2 + ".max").value(contains(20.00)));
    }
}
