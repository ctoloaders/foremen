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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example-based integration test for the offer-package deletion cascade + cleanup (FOR-04-18,
 * task 9.2 — the worked-example companion to Property 2,
 * {@link FinishingMaterialPackageCascadePropertyTest}).
 *
 * <p>Where the property test asserts the invariant across &ge; 100 randomized memberships, this
 * class pins down three explicit, human-readable scenarios that together cover Requirement 8.3 /
 * 3.2-3.4 when a single offer package is deleted via
 * {@link OfferPackageService#deleteById(Long)}:
 * <ol>
 *   <li><b>Join rows removed</b> — deleting the package removes every
 *       {@code finishing_material_packages} row that referenced it (Requirement 3.2).</li>
 *   <li><b>Zero-package material deleted</b> — a finishing material whose ONLY package was the
 *       deleted one is left with zero packages and is therefore deleted (Requirement 3.3).</li>
 *   <li><b>Multi-package material untouched</b> — a finishing material that still holds at least
 *       one other package survives, keeping exactly its remaining packages (Requirement 3.4).</li>
 * </ol>
 *
 * <p>Boot pattern and cascade-FK install are reused verbatim from
 * {@link FinishingMaterialPackageCascadePropertyTest}: {@code @SpringBootTest} + MockMvc security
 * against a Testcontainers PostgreSQL with Hibernate {@code create-drop} DDL. Hibernate's
 * {@code @ManyToMany} DDL does not emit the join table's DB-level {@code ON DELETE CASCADE} that
 * production changeset 060 declares and the service relies on, so
 * {@link #installCascadeForeignKey()} re-creates the {@code fk_fmp_offer_package} FK with
 * {@code ON DELETE CASCADE} before each test — reproducing the migrated production schema.
 *
 * <p>Every row uses a per-run-unique {@code code}/{@code name} suffix so the scenarios are
 * repeatable across runs without manual DB cleanup, and each test tears down its own rows.
 *
 * <p>Feature: FOR-04-18-finishing-materials
 *
 * <p><b>Validates: Requirements 8.3, 3.2, 3.3, 3.4</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Tag("Feature: FOR-04-18-finishing-materials, task 9.2: package deletion cascade example")
class FinishingMaterialPackageCascadeIntegrationTest {

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
     * (idempotently). Hibernate's {@code create-drop} builds the join table but its
     * {@code @ManyToMany} FK carries no cascade, whereas production changeset 060 declares
     * {@code ON DELETE CASCADE}. The service under test relies on that DB cascade to drop join rows
     * when a package is deleted, so we install it here to reproduce the production schema behaviour.
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
    @DisplayName("Deleting an offer package removes its join rows, deletes the material left with "
            + "zero packages, and leaves the multi-package material intact with its remaining packages")
    void deletingAPackageCascadesJoinRowsDeletesEmptiedMaterialAndKeepsSurvivors() {
        String tag = "fmpc-it-" + SEQ.incrementAndGet();

        // ----- Shared mandatory references -----
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

        // ----- Two offer packages: A (to be deleted) and B (kept) -----
        long packageAId = transactionTemplate.execute(status ->
                savePackage("pkg-a-" + tag, 0, tag));
        long packageBId = transactionTemplate.execute(status ->
                savePackage("pkg-b-" + tag, 1, tag));

        // ----- Two materials -----
        //  * soleAMaterial   -> {A}      : must be DELETED when A is deleted (zero packages left).
        //  * multiMaterial   -> {A, B}   : must SURVIVE, keeping only {B} after A is deleted.
        List<Long> ids = transactionTemplate.execute(status -> {
            MaterialCategoryEntity category = entityManager.getReference(
                    MaterialCategoryEntity.class, categoryId);
            MaterialEntity materialRef = entityManager.getReference(
                    MaterialEntity.class, materialRefId);
            MeasurementUnitEntity unit = entityManager.getReference(
                    MeasurementUnitEntity.class, unitId);
            OfferPackageEntity packageA = entityManager.getReference(
                    OfferPackageEntity.class, packageAId);
            OfferPackageEntity packageB = entityManager.getReference(
                    OfferPackageEntity.class, packageBId);

            long soleA = finishingMaterialDao.save(newMaterial(
                    "sole-a-" + tag, category, materialRef, unit, Set.of(packageA))).getId();
            long multi = finishingMaterialDao.save(newMaterial(
                    "multi-" + tag, category, materialRef, unit, Set.of(packageA, packageB))).getId();
            return List.of(soleA, multi);
        });
        long soleAMaterialId = ids.get(0);
        long multiMaterialId = ids.get(1);

        try {
            // Sanity: before the delete, both materials reference package A.
            assertThat(offerPackageIdsFor(soleAMaterialId)).containsExactly(packageAId);
            assertThat(offerPackageIdsFor(multiMaterialId))
                    .containsExactlyInAnyOrder(packageAId, packageBId);

            // ----- Act: delete package A via the service (real ON DELETE CASCADE + cleanup) -----
            offerPackageService.deleteById(packageAId);

            // (1) Requirement 3.2 — no join row references the deleted package anymore.
            assertThat(joinRowCountForPackage(packageAId))
                    .as("no join row may reference the deleted package A")
                    .isZero();

            // (2) Requirement 3.3 — the material whose only package was A is deleted.
            assertThat(finishingMaterialDao.existsById(soleAMaterialId))
                    .as("material whose only package was the deleted one must be deleted")
                    .isFalse();
            assertThat(offerPackageIdsFor(soleAMaterialId))
                    .as("deleted material has no remaining join rows")
                    .isEmpty();

            // (3) Requirement 3.4 — the multi-package material survives with exactly {B}.
            assertThat(finishingMaterialDao.existsById(multiMaterialId))
                    .as("material still holding another package must survive")
                    .isTrue();
            assertThat(offerPackageIdsFor(multiMaterialId))
                    .as("surviving material keeps exactly its remaining package B")
                    .containsExactly(packageBId);

            // Package B itself is untouched.
            assertThat(offerPackageDao.existsById(packageBId))
                    .as("the non-deleted package B is untouched")
                    .isTrue();
        } finally {
            teardown(List.of(soleAMaterialId, multiMaterialId),
                    List.of(packageAId, packageBId), categoryId, materialRefId, unitId);
        }
    }

    // ----- helpers -----

    private long savePackage(String code, int orderNo, String tag) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode(code);
        pkg.setOrderNo(orderNo);
        pkg.setNameRU("Пакет " + tag + " " + orderNo);
        pkg.setNamePL("Pakiet " + tag + " " + orderNo);
        pkg.setActive(true);
        return offerPackageDao.save(pkg).getId();
    }

    private FinishingMaterialEntity newMaterial(String tag, MaterialCategoryEntity category,
                                                MaterialEntity materialRef, MeasurementUnitEntity unit,
                                                Set<OfferPackageEntity> packages) {
        FinishingMaterialEntity material = new FinishingMaterialEntity();
        material.setCategory(category);
        material.setMaterial(materialRef);
        material.setUnit(unit);
        material.setModel("Model " + tag);
        material.setActive(true);
        material.setPackages(new HashSet<>(packages));
        return material;
    }

    /** The offer-package ids currently joined to the given material, read from actual DB state. */
    private Set<Long> offerPackageIdsFor(long materialId) {
        return transactionTemplate.execute(status -> {
            Query query = entityManager.createNativeQuery(
                    "SELECT offer_package_id FROM finishing_material_packages "
                            + "WHERE finishing_material_id = :mat");
            query.setParameter("mat", materialId);
            @SuppressWarnings("unchecked")
            List<Number> rows = query.getResultList();
            return rows.stream().map(Number::longValue).collect(Collectors.toSet());
        });
    }

    private long joinRowCountForPackage(long packageId) {
        return transactionTemplate.execute(status -> {
            Query query = entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM finishing_material_packages WHERE offer_package_id = :pkg");
            query.setParameter("pkg", packageId);
            return ((Number) query.getSingleResult()).longValue();
        });
    }

    /** Delete every row this test created so the scenario is repeatable without manual cleanup. */
    private void teardown(List<Long> materialIds, List<Long> packageIds, long categoryId,
                          long materialRefId, long unitId) {
        transactionTemplate.executeWithoutResult(status -> {
            List<Long> existingMaterials = new ArrayList<>(materialIds.stream()
                    .filter(finishingMaterialDao::existsById)
                    .toList());
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
}
