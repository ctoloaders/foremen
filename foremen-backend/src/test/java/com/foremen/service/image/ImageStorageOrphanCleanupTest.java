package com.foremen.service.image;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MaterialProducerDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.service.ConstructionMaterialService;
import com.foremen.service.model.ConstructionMaterialServiceExtendedModel;
import com.foremen.testsupport.MockMvcSecurityConfig;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;

import jakarta.persistence.EntityManager;

/**
 * Integration test for the shared {@link ImageStorage} orphan-cleanup behaviour (FOR-04-17,
 * Requirement 12.9; underlying Requirements 7.8, 7.9, 7.10).
 *
 * <p>Two triggers plus the reconciliation job are exercised end-to-end against a Testcontainers
 * PostgreSQL, using the real {@link GcsImageStorage} / {@link ImageReconciliationJob} beans:
 * <ul>
 *   <li><b>replace</b> — updating a construction material's {@code image} to a different object key
 *       deletes the previously referenced bucket object (via {@link ConstructionMaterialService}'s
 *       {@code update} → {@link GcsImageStorage#deleteIfOrphan(String)}), Requirement 7.8;</li>
 *   <li><b>detach / delete</b> — nulling the image (or deleting the owning material) deletes the
 *       now-unreferenced bucket object, Requirement 7.9;</li>
 *   <li><b>reconciliation</b> — {@link ImageReconciliationJob#reconcile()} deletes a bucket object
 *       that appears in the bucket listing but is referenced by no DB row, while KEEPING one that a
 *       DB row still references, Requirement 7.10.</li>
 * </ul>
 *
 * <p><b>Active-bean configuration.</b> {@link #configureProperties(DynamicPropertyRegistry)} sets
 * {@code foremen.image-storage.bucket} and {@code foremen.image-storage.cdn-base} so
 * {@link ImageStorageConfiguredCondition} matches and the concrete {@link GcsImageStorage} +
 * {@link ImageReconciliationJob} beans are wired (not the {@link DisabledImageStorage} fallback).
 *
 * <p><b>Mocked GCS client.</b> {@link GcsImageStorage} normally builds a real Google Cloud
 * {@link Storage} client in a {@code @PostConstruct}; this test replaces that private {@code storage}
 * field with a Mockito mock via {@link ReflectionTestUtils} in {@link #injectMockStorage()} —
 * exactly the least-invasive injection approach {@code ImageUrlResolutionPropertyTest} uses — so no
 * real bucket is touched. Bucket deletes are asserted through {@code verify(storage).delete(...)},
 * and {@code storage.list(bucket)} is stubbed for the reconciliation scenario. The referenced-vs-
 * orphan distinction is driven by the real {@link ImageReferenceLookup} querying the actual DB rows
 * ({@code construction_materials.image} / {@code material_producers.image}).
 *
 * <p>Boots exactly like the other FOR-04-17 integration tests (mirrors
 * {@code ConstructionMaterialPackageCascadePropertyTest}: {@code @SpringBootTest} + MockMvc security
 * against a Testcontainers PostgreSQL with Hibernate {@code create-drop} DDL). Each test uses a
 * per-invocation-unique key/code suffix and tears down its own rows so the scenarios are repeatable
 * without manual DB cleanup.
 *
 * <p>Feature: FOR-04-17-construction-materials
 *
 * <p><b>Validates: Requirements 7.8, 7.9, 7.10, 12.9</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Tag("Feature: FOR-04-17-construction-materials, ImageStorage orphan cleanup (Requirement 12.9)")
class ImageStorageOrphanCleanupTest {

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
    private ConstructionMaterialService constructionMaterialService;
    @Autowired
    private GcsImageStorage gcsImageStorage;
    @Autowired
    private ImageReconciliationJob reconciliationJob;
    @Autowired
    private ConstructionMaterialDao constructionMaterialDao;
    @Autowired
    private ConstructionMaterialTypeDao constructionMaterialTypeDao;
    @Autowired
    private MaterialProducerDao materialProducerDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private CurrencyDao currencyDao;
    @Autowired
    private OfferPackageDao offerPackageDao;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Storage storage;

    /**
     * Replace the {@link GcsImageStorage} bean's private {@code storage} field with a fresh Mockito
     * mock (the production code would build a real GCS client in {@code @PostConstruct}). Matches
     * {@code ImageUrlResolutionPropertyTest}'s injection approach; a fresh mock per test isolates the
     * {@code verify(...)} interaction counts.
     */
    @BeforeEach
    void injectMockStorage() {
        storage = Mockito.mock(Storage.class);
        ReflectionTestUtils.setField(gcsImageStorage, "storage", storage);
    }

    // ==========================================================================================
    // Trigger 1 — replace (Requirement 7.8)
    // ==========================================================================================

    @Nested
    @DisplayName("replace trigger (Requirement 7.8)")
    class ReplaceTrigger {

        @Test
        @DisplayName("replacing an entity's image deletes the previously-referenced bucket object")
        void replacingImageDeletesPreviousObject() {
            String tag = uniqueTag();
            Refs refs = createReferences(tag);
            String oldKey = "construction-materials/" + tag + "-old.png";
            String newKey = "construction-materials/" + tag + "-new.png";

            Long materialId = createMaterial(tag, refs, oldKey);

            // Update the image to a DIFFERENT object key via the owning service (task 5.2 update path).
            ConstructionMaterialServiceExtendedModel update = new ConstructionMaterialServiceExtendedModel();
            update.setNameRU("Мат " + tag);
            update.setNamePL("Mat " + tag);
            update.setTypeId(refs.typeId());
            update.setUnitId(refs.unitId());
            update.setCurrencyId(refs.currencyId());
            update.setImage(newKey);
            constructionMaterialService.update(materialId, update);

            // The previous object is now unreferenced (the row holds newKey) → it must be deleted,
            // and only that object.
            assertDeletedExactly(oldKey);

            deleteMaterialsRaw(List.of(materialId));
            teardown(refs);
        }
    }

    // ==========================================================================================
    // Trigger 2 — detach (null) / delete (Requirement 7.9)
    // ==========================================================================================

    @Nested
    @DisplayName("detach / delete trigger (Requirement 7.9)")
    class DetachDeleteTrigger {

        @Test
        @DisplayName("detaching (nulling) an entity's image deletes the now-unreferenced object")
        void detachingImageDeletesObject() {
            String tag = uniqueTag();
            Refs refs = createReferences(tag);
            String key = "construction-materials/" + tag + "-detach.png";

            Long materialId = createMaterial(tag, refs, key);

            // Detach the image (null it) via the owning service (task 5.2 setPropertiesToNull path).
            constructionMaterialService.setPropertiesToNull(materialId, Set.of("image"));

            assertDeletedExactly(key);

            deleteMaterialsRaw(List.of(materialId));
            teardown(refs);
        }

        @Test
        @DisplayName("deleting the owning entity deletes the now-unreferenced object")
        void deletingEntityDeletesObject() {
            String tag = uniqueTag();
            Refs refs = createReferences(tag);
            String key = "construction-materials/" + tag + "-delete.png";

            Long materialId = createMaterial(tag, refs, key);

            // Delete the material via the owning service (task 5.2 deleteById path).
            constructionMaterialService.deleteById(materialId);

            assertDeletedExactly(key);

            teardown(refs);
        }
    }

    // ==========================================================================================
    // Reconciliation job (Requirement 7.10)
    // ==========================================================================================

    @Nested
    @DisplayName("reconciliation job (Requirement 7.10)")
    class Reconciliation {

        @Test
        @DisplayName("reconcile() deletes an unreferenced bucket object while keeping a referenced one")
        void reconcileDeletesOrphanKeepsReferenced() {
            String tag = uniqueTag();
            Refs refs = createReferences(tag);

            // referencedKey is held by a real DB row; orphanKey is present only in the bucket listing.
            String referencedKey = "construction-materials/" + tag + "-referenced.png";
            String orphanKey = "construction-materials/" + tag + "-orphan.png";

            Long materialId = createMaterial(tag, refs, referencedKey);

            // Stub the bucket listing to contain BOTH the referenced key and the orphan key.
            stubBucketListing(referencedKey, orphanKey);

            reconciliationJob.reconcile();

            // Only the orphan bucket object (referenced by no DB row) is deleted; the referenced one
            // is kept.
            ArgumentCaptor<BlobId> captor = ArgumentCaptor.forClass(BlobId.class);
            Mockito.verify(storage, Mockito.atLeastOnce()).delete(captor.capture());
            List<String> deletedKeys = captor.getAllValues().stream().map(BlobId::getName).toList();
            assertThat(deletedKeys)
                    .as("reconciliation deletes the orphan bucket object")
                    .contains(orphanKey);
            assertThat(deletedKeys)
                    .as("reconciliation keeps the DB-referenced bucket object")
                    .doesNotContain(referencedKey);

            deleteMaterialsRaw(List.of(materialId));
            teardown(refs);
        }
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    /** Assert the mock bucket saw exactly one delete, for the given object key, and no other. */
    private void assertDeletedExactly(String expectedKey) {
        ArgumentCaptor<BlobId> captor = ArgumentCaptor.forClass(BlobId.class);
        Mockito.verify(storage).delete(captor.capture());
        assertThat(captor.getValue().getBucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().getName()).isEqualTo(expectedKey);
    }

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
        return "iso-" + SEQ.incrementAndGet();
    }

    /** Create the mandatory references (type, unit, currency, one package) for a material. */
    private Refs createReferences(String tag) {
        return transactionTemplate.execute(status -> {
            ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
            type.setCode("type-" + tag);
            type.setNameRU("Тип " + tag);
            type.setNamePL("Typ " + tag);
            type.setActive(true);
            long typeId = constructionMaterialTypeDao.save(type).getId();

            MeasurementUnitEntity unit = new MeasurementUnitEntity();
            unit.setCode("unit-" + tag);
            unit.setNameRU("Ед " + tag);
            unit.setNamePL("Jedn " + tag);
            unit.setActive(true);
            long unitId = measurementUnitDao.save(unit).getId();

            CurrencyEntity currency = new CurrencyEntity();
            currency.setCode("cur" + tag);
            currency.setSymbol("¤");
            currency.setNameRU("Вал " + tag);
            currency.setNamePL("Wal " + tag);
            currency.setActive(true);
            long currencyId = currencyDao.save(currency).getId();

            OfferPackageEntity pkg = new OfferPackageEntity();
            pkg.setCode("pkg-" + tag);
            pkg.setOrderNo(0);
            pkg.setNameRU("Пакет " + tag);
            pkg.setNamePL("Pakiet " + tag);
            pkg.setActive(true);
            long packageId = offerPackageDao.save(pkg).getId();

            return new Refs(typeId, unitId, currencyId, packageId);
        });
    }

    /** Create a construction material through the service, carrying the given image object key. */
    private Long createMaterial(String tag, Refs refs, String imageKey) {
        ConstructionMaterialServiceExtendedModel model = new ConstructionMaterialServiceExtendedModel();
        model.setNameRU("Мат " + tag);
        model.setNamePL("Mat " + tag);
        model.setTypeId(refs.typeId());
        model.setUnitId(refs.unitId());
        model.setCurrencyId(refs.currencyId());
        model.setRetailNet(new BigDecimal("10.00"));
        model.setImage(imageKey);
        model.setActive(true);
        return constructionMaterialService.create(model).getId();
    }

    /** Remove any surviving material rows (removes their join rows first) in their own transaction. */
    private void deleteMaterialsRaw(List<Long> materialIds) {
        transactionTemplate.executeWithoutResult(status -> {
            List<Long> existing = materialIds.stream()
                    .filter(constructionMaterialDao::existsById)
                    .toList();
            if (!existing.isEmpty()) {
                constructionMaterialDao.deleteAllById(existing);
            }
        });
    }

    /** Delete the reference rows this test created so the scenario is repeatable without cleanup. */
    private void teardown(Refs refs) {
        transactionTemplate.executeWithoutResult(status -> {
            if (offerPackageDao.existsById(refs.packageId())) {
                offerPackageDao.deleteById(refs.packageId());
            }
            constructionMaterialTypeDao.deleteById(refs.typeId());
            measurementUnitDao.deleteById(refs.unitId());
            currencyDao.deleteById(refs.currencyId());
        });
    }

    /** Mandatory references shared by a material fixture. */
    private record Refs(long typeId, long unitId, long currencyId, long packageId) {
    }
}
