package com.foremen.service;

import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MaterialCategoryDao;
import com.foremen.dao.MaterialDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.service.image.GcsImageStorage;
import com.foremen.service.image.ImageReconciliationJob;
import com.foremen.service.image.ImageReferenceLookup;
import com.foremen.service.model.FinishingMaterialServiceExtendedModel;
import com.foremen.testsupport.MockMvcSecurityConfig;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the shared {@link ImageReferenceLookup}'s coverage of the
 * {@code finishing_materials.photo} column (FOR-04-18, task 9.3 — Requirements 8.9, 4.5).
 *
 * <p>FOR-04-18's ONLY additive touch to the shared FOR-04-17 image seam is registering
 * {@code finishing_materials.photo} in {@link ImageReferenceLookup}'s known image columns (task 4.1)
 * so both the eager {@code deleteIfOrphan} trigger and the periodic {@link ImageReconciliationJob}
 * count finishing-material photo references. This test mirrors the FOR-04-17 sibling
 * {@code ImageStorageOrphanCleanupTest} reconciliation scenario exactly, but drives the
 * {@code finishing_materials.photo} column to prove:
 * <ul>
 *   <li>a photo still referenced by a finishing material is NOT reclaimed by reconciliation
 *       (kept), and</li>
 *   <li>a detached (nulled) photo — now referenced by no finishing material — IS reclaimed
 *       (deleted)</li>
 * </ul>
 * both against a bucket listing that contains both keys, with the referenced-vs-orphan distinction
 * driven by the real {@link ImageReferenceLookup} querying actual {@code finishing_materials} rows.
 *
 * <p><b>Active-bean configuration &amp; mocked GCS client.</b> Boots exactly like
 * {@code ImageStorageOrphanCleanupTest}: {@code @SpringBootTest} + MockMvc security against a
 * Testcontainers PostgreSQL with Hibernate {@code create-drop} DDL, with
 * {@code foremen.image-storage.bucket} + {@code cdn-base} set so the concrete
 * {@link GcsImageStorage} + {@link ImageReconciliationJob} beans are wired. The private GCS
 * {@code storage} field is replaced with a Mockito mock via {@link ReflectionTestUtils} in
 * {@code @BeforeEach} so no real bucket is touched; bucket deletes are asserted through
 * {@code verify(storage).delete(...)} and {@code storage.list(bucket)} is stubbed for the
 * reconciliation scenario.
 *
 * <p>Each scenario uses a per-run-unique {@code code}/{@code name} suffix and tears down its own rows
 * so the scenarios are repeatable without manual DB cleanup.
 *
 * <p>Feature: FOR-04-18-finishing-materials
 *
 * <p><b>Validates: Requirements 8.9, 4.5</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Tag("Feature: FOR-04-18-finishing-materials, task 9.3: ImageReferenceLookup counts finishing_materials.photo (Requirements 8.9, 4.5)")
class FinishingMaterialImageReferenceLookupIntegrationTest {

    private static final String BUCKET = "test-bucket";
    private static final String CDN_BASE = "https://cdn.example.com";

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
        // Configure image storage so the GCS implementation + reconciliation job are the active
        // beans (bucket + cdn-base present → ImageStorageConfiguredCondition matches).
        registry.add("foremen.image-storage.bucket", () -> BUCKET);
        registry.add("foremen.image-storage.cdn-base", () -> CDN_BASE);
    }

    /** Per-run-unique suffix so generated keys/codes never collide across runs (repeatability). */
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private FinishingMaterialService finishingMaterialService;
    @Autowired
    private GcsImageStorage gcsImageStorage;
    @Autowired
    private ImageReconciliationJob reconciliationJob;
    @Autowired
    private ImageReferenceLookup imageReferenceLookup;
    @Autowired
    private FinishingMaterialDao finishingMaterialDao;
    @Autowired
    private MaterialCategoryDao materialCategoryDao;
    @Autowired
    private MaterialDao materialDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private OfferPackageDao offerPackageDao;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Storage storage;

    @BeforeEach
    void injectMockStorage() {
        storage = Mockito.mock(Storage.class);
        ReflectionTestUtils.setField(gcsImageStorage, "storage", storage);
    }

    @Test
    @DisplayName("reconcile() keeps a photo still referenced by a finishing material and reclaims a "
            + "detached (nulled) one")
    void reconcileKeepsReferencedFinishingPhotoAndReclaimsDetachedOne() {
        String tag = uniqueTag();
        Refs refs = createReferences(tag);

        // referencedKey stays on a finishing material; detachedKey starts on a material then is nulled.
        String referencedKey = "finishing-materials/" + tag + "-referenced.png";
        String detachedKey = "finishing-materials/" + tag + "-detached.png";

        Long referencedMaterialId = createMaterial(tag + "-ref", refs, referencedKey);
        Long detachedMaterialId = createMaterial(tag + "-det", refs, detachedKey);

        try {
            // Sanity: while both photos are attached, the real lookup counts BOTH references so
            // neither would be reclaimed (Requirement 4.5).
            assertThat(imageReferenceLookup.isReferenced(referencedKey))
                    .as("a photo attached to a finishing material is referenced")
                    .isTrue();
            assertThat(imageReferenceLookup.isReferenced(detachedKey))
                    .as("a photo attached to a finishing material is referenced")
                    .isTrue();

            // Detach the second material's photo (null it) via the owning service. This also fires
            // the eager deleteIfOrphan trigger for detachedKey — reset the mock afterwards so the
            // reconciliation assertion sees only the reconcile()-driven deletes.
            finishingMaterialService.setPropertiesToNull(detachedMaterialId, Set.of("photo"));
            Mockito.reset(storage);

            // Now the detached key is referenced by no finishing material, while the referenced one
            // still is.
            assertThat(imageReferenceLookup.isReferenced(detachedKey))
                    .as("a detached (nulled) photo is no longer referenced")
                    .isFalse();
            assertThat(imageReferenceLookup.isReferenced(referencedKey))
                    .as("the still-attached photo remains referenced")
                    .isTrue();

            // Stub the bucket listing to contain BOTH keys, then run reconciliation.
            stubBucketListing(referencedKey, detachedKey);
            reconciliationJob.reconcile();

            // Only the detached (unreferenced) bucket object is deleted; the still-referenced one is
            // kept (Requirements 8.9, 4.5).
            ArgumentCaptor<BlobId> captor = ArgumentCaptor.forClass(BlobId.class);
            Mockito.verify(storage, Mockito.atLeastOnce()).delete(captor.capture());
            List<String> deletedKeys = captor.getAllValues().stream().map(BlobId::getName).toList();
            assertThat(deletedKeys)
                    .as("reconciliation reclaims the detached (unreferenced) finishing-material photo")
                    .contains(detachedKey);
            assertThat(deletedKeys)
                    .as("reconciliation keeps a photo still referenced by a finishing material")
                    .doesNotContain(referencedKey);
        } finally {
            deleteMaterialsRaw(List.of(referencedMaterialId, detachedMaterialId));
            teardown(refs);
        }
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    /** Stub {@code storage.list(bucket)} to return a {@link Page} iterating blobs for the given keys. */
    private void stubBucketListing(String... keys) {
        List<Blob> blobs = java.util.Arrays.stream(keys).map(key -> {
            Blob blob = Mockito.mock(Blob.class);
            Mockito.when(blob.getName()).thenReturn(key);
            return blob;
        }).toList();

        @SuppressWarnings("unchecked")
        Page<Blob> page = Mockito.mock(Page.class);
        Mockito.when(page.iterateAll()).thenReturn(blobs);
        Mockito.when(storage.list(BUCKET)).thenReturn(page);
    }

    private String uniqueTag() {
        return "fmirl-it-" + SEQ.incrementAndGet();
    }

    /** Create the mandatory references (category, material, unit, one package) for a material. */
    private Refs createReferences(String tag) {
        return transactionTemplate.execute(status -> {
            MaterialCategoryEntity category = new MaterialCategoryEntity();
            category.setCode("cat-" + tag);
            category.setNameRU("Категория " + tag);
            category.setNamePL("Kategoria " + tag);
            category.setActive(true);
            long categoryId = materialCategoryDao.save(category).getId();

            MaterialEntity material = new MaterialEntity();
            material.setCode("mat-" + tag);
            material.setNameRU("Материал " + tag);
            material.setNamePL("Materiał " + tag);
            material.setActive(true);
            long materialRefId = materialDao.save(material).getId();

            MeasurementUnitEntity unit = new MeasurementUnitEntity();
            unit.setCode("unit-" + tag);
            unit.setNameRU("Ед " + tag);
            unit.setNamePL("Jedn " + tag);
            unit.setActive(true);
            long unitId = measurementUnitDao.save(unit).getId();

            OfferPackageEntity pkg = new OfferPackageEntity();
            pkg.setCode("pkg-" + tag);
            pkg.setOrderNo(0);
            pkg.setNameRU("Пакет " + tag);
            pkg.setNamePL("Pakiet " + tag);
            pkg.setActive(true);
            long packageId = offerPackageDao.save(pkg).getId();

            return new Refs(categoryId, materialRefId, unitId, packageId);
        });
    }

    /** Create a finishing material through the service, carrying the given photo object key. */
    private Long createMaterial(String tag, Refs refs, String photoKey) {
        FinishingMaterialServiceExtendedModel model = new FinishingMaterialServiceExtendedModel();
        model.setCategoryId(refs.categoryId());
        model.setMaterialId(refs.materialRefId());
        model.setUnitId(refs.unitId());
        model.setOfferPackageIds(new HashSet<>(Set.of(refs.packageId())));
        model.setModel("Model " + tag);
        model.setPhoto(photoKey);
        model.setActive(true);
        return finishingMaterialService.create(model).getId();
    }

    /** Remove any surviving material rows in their own transaction. */
    private void deleteMaterialsRaw(List<Long> materialIds) {
        transactionTemplate.executeWithoutResult(status -> {
            List<Long> existing = materialIds.stream()
                    .filter(finishingMaterialDao::existsById)
                    .toList();
            if (!existing.isEmpty()) {
                finishingMaterialDao.deleteAllById(existing);
            }
        });
    }

    /** Delete the reference rows this test created so the scenario is repeatable without cleanup. */
    private void teardown(Refs refs) {
        transactionTemplate.executeWithoutResult(status -> {
            if (offerPackageDao.existsById(refs.packageId())) {
                offerPackageDao.deleteById(refs.packageId());
            }
            materialDao.deleteById(refs.materialRefId());
            materialCategoryDao.deleteById(refs.categoryId());
            measurementUnitDao.deleteById(refs.unitId());
        });
    }

    /** Mandatory references shared by a finishing-material fixture. */
    private record Refs(long categoryId, long materialRefId, long unitId, long packageId) {
    }
}
