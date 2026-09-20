package com.foremen.service;

import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MaterialCategoryDao;
import com.foremen.dao.MaterialDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the offer-package deletion cascade + cleanup (FOR-04-18, Property 2 —
 * "Deleting an offer package preserves the '&ge; 1 package' invariant").
 *
 * <p>This is a Testcontainers-backed database property test (unlike the pure in-memory property
 * tests in this spec) because it exercises the real behaviour that only exists against PostgreSQL:
 * the {@code finishing_material_packages.offer_package_id} foreign key declared
 * {@code ON DELETE CASCADE} (changeset 060), combined with the
 * {@link OfferPackageService#deleteById(Long)} override (task 3.1) that, in the same transaction,
 * deletes every finishing material the cascade just left with zero packages (Requirements 3.2,
 * 3.3, 3.4).
 *
 * <p>The class mirrors the FOR-04-17 sibling {@code ConstructionMaterialPackageCascadePropertyTest}
 * exactly: {@code @SpringBootTest} + MockMvc security against a Testcontainers PostgreSQL with
 * Hibernate {@code create-drop} DDL. Hibernate's {@code @ManyToMany} DDL does NOT emit the join
 * table's DB-level {@code ON DELETE CASCADE} that the production changeset (060) declares and the
 * service relies on, so {@link #installCascadeForeignKey()} re-creates the
 * {@code fk_fmp_offer_package} foreign key with {@code ON DELETE CASCADE} once per iteration —
 * making this test exercise the same cascade + cleanup behaviour that runs against the migrated
 * schema in production.
 *
 * <p>Because jqwik is not wired into the Spring test context in this project (no
 * {@code net.jqwik.spring} hook), the &ge; 100 randomized iterations are driven by a deterministic
 * {@link Random} loop inside a single Spring-managed test rather than a {@code @Property} method,
 * so the autowired {@link OfferPackageService} / DAOs are injected. Each iteration generates a
 * fresh set of offer packages and finishing materials (each material referencing a random
 * non-empty subset of the packages), deletes one random package via the service, then asserts the
 * invariant. Every row uses a per-iteration-unique {@code code}/{@code name} so the scenario is
 * repeatable across runs without manual DB cleanup, and each iteration tears down its own rows.
 *
 * <p>Feature: FOR-04-18-finishing-materials, Property 2
 *
 * <p><b>Validates: Requirements 3.2, 3.3, 3.4</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Tag("Feature: FOR-04-18-finishing-materials, Property 2: Deleting an offer package preserves the >= 1 package invariant")
class FinishingMaterialPackageCascadePropertyTest {

    private static final int ITERATIONS = 120;

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

    /** Per-run-unique suffix so generated codes never collide across runs (repeatability). */
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private OfferPackageService offerPackageService;
    @Autowired
    private OfferPackageDao offerPackageDao;
    @Autowired
    private FinishingMaterialDao finishingMaterialDao;
    @Autowired
    private MaterialCategoryDao materialCategoryDao;
    @Autowired
    private MaterialDao materialDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * Re-create the join table's {@code offer_package_id} foreign key with {@code ON DELETE CASCADE}
     * (idempotently). Hibernate's {@code create-drop} builds the join table but its {@code @ManyToMany}
     * FK carries no cascade, whereas production changeset 060 declares {@code ON DELETE CASCADE}. The
     * service under test relies on that DB cascade to drop join rows when a package is deleted, so we
     * install it here to reproduce the production schema behaviour.
     */
    @BeforeEach
    void installCascadeForeignKey() {
        transactionTemplate.executeWithoutResult(status -> {
            Query drop = entityManager.createNativeQuery(
                    "ALTER TABLE finishing_material_packages "
                            + "DROP CONSTRAINT IF EXISTS fk_fmp_offer_package_cascade");
            drop.executeUpdate();
            // Drop any Hibernate-generated FK on offer_package_id so only the cascading one remains.
            Query dropGenerated = entityManager.createNativeQuery(
                    "DO $$ DECLARE c text; BEGIN "
                            + "FOR c IN SELECT conname FROM pg_constraint "
                            + "WHERE conrelid = 'finishing_material_packages'::regclass "
                            + "AND contype = 'f' "
                            + "AND 'offer_package_id' = ANY (SELECT attname FROM pg_attribute "
                            + "WHERE attrelid = conrelid AND attnum = ANY (conkey)) "
                            + "AND conname <> 'fk_fmp_offer_package_cascade' "
                            + "LOOP EXECUTE 'ALTER TABLE finishing_material_packages DROP CONSTRAINT ' || quote_ident(c); "
                            + "END LOOP; END $$;");
            dropGenerated.executeUpdate();
            Query add = entityManager.createNativeQuery(
                    "ALTER TABLE finishing_material_packages "
                            + "ADD CONSTRAINT fk_fmp_offer_package_cascade "
                            + "FOREIGN KEY (offer_package_id) REFERENCES offer_packages(id) "
                            + "ON DELETE CASCADE");
            add.executeUpdate();
        });
    }

    @Test
    @DisplayName("Property 2: deleting an offer package drops its join rows, deletes zero-package "
            + "finishing materials, and leaves every surviving material with exactly its remaining packages")
    void deletingAPackagePreservesTheAtLeastOnePackageInvariant() {
        // A deterministic seed keeps failures reproducible while still spanning the input space.
        Random random = new Random(20240618L);

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            runOneIteration(random, iteration);
        }
    }

    /**
     * One property iteration: build packages + finishing materials with random memberships, delete
     * a random package, and assert the three invariants against freshly-queried DB state. Setup and
     * teardown run in their own transactions; the delete under test goes through the service (its own
     * transaction) so the DB cascade + cleanup genuinely commit before re-querying.
     */
    private void runOneIteration(Random random, int iteration) {
        long run = SEQ.incrementAndGet();
        String tag = "fmpc-" + run + "-" + iteration;

        // Shared mandatory references for every finishing material this iteration.
        long categoryId = transactionTemplate.execute(status -> {
            MaterialCategoryEntity category = new MaterialCategoryEntity();
            category.setCode("cat-" + tag);
            category.setNameRU("Категория " + tag);
            category.setNamePL("Kategoria " + tag);
            category.setActive(true);
            return materialCategoryDao.save(category).getId();
        });
        long materialRefId = transactionTemplate.execute(status -> {
            MaterialEntity mat = new MaterialEntity();
            mat.setCode("mat-" + tag);
            mat.setNameRU("Материал " + tag);
            mat.setNamePL("Materiał " + tag);
            mat.setActive(true);
            return materialDao.save(mat).getId();
        });
        long unitId = transactionTemplate.execute(status -> {
            MeasurementUnitEntity unit = new MeasurementUnitEntity();
            unit.setCode("unit-" + tag);
            unit.setNameRU("Ед " + tag);
            unit.setNamePL("Jedn " + tag);
            unit.setActive(true);
            return measurementUnitDao.save(unit).getId();
        });

        // A random-sized set of offer packages (2..5) so a delete can leave surviving packages.
        int packageCount = 2 + random.nextInt(4);
        List<Long> packageIds = transactionTemplate.execute(status -> {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < packageCount; i++) {
                OfferPackageEntity pkg = new OfferPackageEntity();
                pkg.setCode("pkg-" + tag + "-" + i);
                pkg.setOrderNo(i);
                pkg.setNameRU("Пакет " + tag + " " + i);
                pkg.setNamePL("Pakiet " + tag + " " + i);
                pkg.setActive(true);
                ids.add(offerPackageDao.save(pkg).getId());
            }
            return ids;
        });

        // A random-sized set of finishing materials, each referencing a random NON-EMPTY subset.
        int materialCount = 1 + random.nextInt(6);
        // Record the intended membership per material so we can assert survivors precisely.
        List<Set<Long>> membershipByMaterial = new ArrayList<>();
        for (int i = 0; i < materialCount; i++) {
            membershipByMaterial.add(randomNonEmptySubset(packageIds, random));
        }

        List<Long> materialIds = transactionTemplate.execute(status -> {
            MaterialCategoryEntity category = entityManager.getReference(
                    MaterialCategoryEntity.class, categoryId);
            MaterialEntity materialRef = entityManager.getReference(
                    MaterialEntity.class, materialRefId);
            MeasurementUnitEntity unit = entityManager.getReference(
                    MeasurementUnitEntity.class, unitId);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < materialCount; i++) {
                FinishingMaterialEntity material = new FinishingMaterialEntity();
                material.setCategory(category);
                material.setMaterial(materialRef);
                material.setUnit(unit);
                material.setModel("Model " + tag + " " + i);
                material.setActive(true);
                Set<OfferPackageEntity> pkgs = new HashSet<>();
                for (Long pkgId : membershipByMaterial.get(i)) {
                    pkgs.add(entityManager.getReference(OfferPackageEntity.class, pkgId));
                }
                material.setPackages(pkgs);
                ids.add(finishingMaterialDao.save(material).getId());
            }
            return ids;
        });

        // Delete a random package via the service (real ON DELETE CASCADE + cleanup, own tx).
        Long deletedPackageId = packageIds.get(random.nextInt(packageIds.size()));
        offerPackageService.deleteById(deletedPackageId);

        try {
            assertInvariants(iteration, deletedPackageId, materialIds, membershipByMaterial,
                    packageIds);
        } finally {
            teardown(materialIds, packageIds, categoryId, materialRefId, unitId);
        }
    }

    /**
     * Assert the three invariants against fresh DB state:
     * <ol>
     *   <li>no join row references the deleted package,</li>
     *   <li>every surviving material keeps exactly its remaining packages (its original set minus
     *       the deleted package),</li>
     *   <li>no surviving material has zero packages — materials whose only packages were the deleted
     *       one are gone.</li>
     * </ol>
     */
    private void assertInvariants(int iteration, Long deletedPackageId, List<Long> materialIds,
                                  List<Set<Long>> membershipByMaterial, List<Long> packageIds) {
        transactionTemplate.executeWithoutResult(status -> {
            // (1) No join row references the deleted package anywhere.
            Query danglingQuery = entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM finishing_material_packages WHERE offer_package_id = :pkg");
            danglingQuery.setParameter("pkg", deletedPackageId);
            long danglingRows = ((Number) danglingQuery.getSingleResult()).longValue();
            assertThat(danglingRows)
                    .as("iteration %d: no join row may reference the deleted package %d",
                            iteration, deletedPackageId)
                    .isZero();

            for (int i = 0; i < materialIds.size(); i++) {
                Long materialId = materialIds.get(i);
                Set<Long> expectedRemaining = membershipByMaterial.get(i).stream()
                        .filter(pkgId -> !pkgId.equals(deletedPackageId))
                        .collect(Collectors.toSet());

                Query membershipQuery = entityManager.createNativeQuery(
                        "SELECT offer_package_id FROM finishing_material_packages "
                                + "WHERE finishing_material_id = :mat");
                membershipQuery.setParameter("mat", materialId);
                @SuppressWarnings("unchecked")
                List<Number> rows = membershipQuery.getResultList();
                Set<Long> actualRemaining = rows.stream()
                        .map(Number::longValue)
                        .collect(Collectors.toSet());

                boolean materialExists = finishingMaterialDao.existsById(materialId);

                if (expectedRemaining.isEmpty()) {
                    // (3) A material left with zero packages must have been deleted entirely.
                    assertThat(materialExists)
                            .as("iteration %d: finishing material %d whose only package was the "
                                    + "deleted one must be deleted", iteration, materialId)
                            .isFalse();
                    assertThat(actualRemaining)
                            .as("iteration %d: deleted finishing material %d has no join rows",
                                    iteration, materialId)
                            .isEmpty();
                } else {
                    // (2) & (3) A survivor keeps EXACTLY its remaining packages and never zero.
                    assertThat(materialExists)
                            .as("iteration %d: finishing material %d still holding %d package(s) "
                                    + "must survive", iteration, materialId, expectedRemaining.size())
                            .isTrue();
                    assertThat(actualRemaining)
                            .as("iteration %d: surviving finishing material %d keeps exactly its "
                                    + "remaining packages", iteration, materialId)
                            .isEqualTo(expectedRemaining);
                    assertThat(actualRemaining)
                            .as("iteration %d: surviving finishing material %d must have >= 1 package",
                                    iteration, materialId)
                            .isNotEmpty();
                }
            }
        });
    }

    /** Delete every row this iteration created so the scenario is repeatable without manual cleanup. */
    private void teardown(List<Long> materialIds, List<Long> packageIds, long categoryId,
                          long materialRefId, long unitId) {
        transactionTemplate.executeWithoutResult(status -> {
            // Surviving materials first (removes their join rows), then remaining packages, then refs.
            List<Long> existingMaterials = materialIds.stream()
                    .filter(finishingMaterialDao::existsById)
                    .toList();
            if (!existingMaterials.isEmpty()) {
                finishingMaterialDao.deleteAllById(existingMaterials);
            }
            List<Long> existingPackages = packageIds.stream()
                    .filter(offerPackageDao::existsById)
                    .toList();
            if (!existingPackages.isEmpty()) {
                offerPackageDao.deleteAllById(existingPackages);
            }
            materialDao.deleteById(materialRefId);
            materialCategoryDao.deleteById(categoryId);
            measurementUnitDao.deleteById(unitId);
        });
    }

    /** A random non-empty subset of the given package ids. */
    private Set<Long> randomNonEmptySubset(List<Long> packageIds, Random random) {
        Set<Long> subset = new HashSet<>();
        for (Long id : packageIds) {
            if (random.nextBoolean()) {
                subset.add(id);
            }
        }
        if (subset.isEmpty()) {
            subset.add(packageIds.get(random.nextInt(packageIds.size())));
        }
        return subset;
    }
}
