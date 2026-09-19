package com.foremen.service;

import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MaterialCategoryDao;
import com.foremen.dao.MaterialDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.ResourceDao;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.service.image.GcsImageStorage;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.FinishingMaterialServiceExtendedModel;
import com.foremen.testsupport.MockMvcSecurityConfig;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for finishing-material photo storage reuse (FOR-04-18, task 9.3 —
 * Requirements 8.6, 4.4, 4.7).
 *
 * <p>FOR-04-18 does NOT reimplement image storage; the finishing-material photo consumes the shared
 * FOR-04-17 {@link ImageStorage} / {@code POST /api/images} seam verbatim. This test therefore
 * mirrors the FOR-04-17 sibling ITs ({@code ImageStorageUploadIntegrationTest} /
 * {@code ImageStorageRejectionTest}) exactly, but pins the finishing-material vertical:
 * <ul>
 *   <li><b>Photo object-key round-trip</b> — creating a finishing material carrying a stored
 *       {@code photo} object key exposes a resolved {@code photoUrl} ({@code cdnBase + "/" + key}) on
 *       both the single read ({@code findByIdLocalized}) and the list read ({@code find}), and a
 *       finishing material with a null photo resolves to a null {@code photoUrl}
 *       (Requirements 8.6, 4.4).</li>
 *   <li><b>Upload rejection reuse</b> — a non-image content type and an over-size payload are each
 *       rejected by the shared {@code POST /api/images} endpoint with 400, storing nothing, when the
 *       upload targets the {@code finishing-materials} entity kind / {@code MATERIALS_FINISHING}
 *       resource (Requirement 4.7, reusing the shared service's existing validation).</li>
 * </ul>
 *
 * <p><b>Active-bean configuration &amp; mocked GCS client.</b> Boots exactly like the sibling
 * FOR-04-17 ITs: {@code @SpringBootTest} + MockMvc security against a Testcontainers PostgreSQL with
 * Hibernate {@code create-drop} DDL, with {@code foremen.image-storage.bucket} +
 * {@code cdn-base} set so the concrete {@link GcsImageStorage} is the active {@link ImageStorage},
 * and a small {@code max-upload-size} so the over-size boundary is reachable. The private GCS
 * {@code storage} field (normally built in a {@code @PostConstruct}) is replaced with a Mockito mock
 * in {@code @BeforeEach} via {@link ReflectionTestUtils} so no real bucket is touched and each
 * rejection can assert that nothing was stored ({@code create(...)} never invoked). The
 * integration-test profile disables Liquibase (schema from JPA entities), so the
 * {@code MATERIALS_FINISHING} resource row the {@code ImageController} validates the dynamic
 * {@code resource} param against is inserted by hand.
 *
 * <p>Each scenario uses a per-run-unique {@code code}/{@code name} suffix and tears down its own rows
 * so the scenarios are repeatable without manual DB cleanup.
 *
 * <p>Feature: FOR-04-18-finishing-materials
 *
 * <p><b>Validates: Requirements 8.6, 4.4, 4.7</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Tag("Feature: FOR-04-18-finishing-materials, task 9.3: photo storage reuse (Requirements 8.6, 4.4, 4.7)")
class FinishingMaterialPhotoIntegrationTest {

    private static final String BUCKET = "test-bucket";
    private static final String CDN_BASE = "https://cdn.example.com/media";

    /** Small enough that a modest payload exceeds it, exercising the over-size rejection (Req 4.7). */
    private static final int MAX_UPLOAD_BYTES = 1024;

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
        // Configure image storage so GcsImageStorage is the active ImageStorage bean (Req 4.1):
        // bucket + cdn-base present → ImageStorageConfiguredCondition matches.
        registry.add("foremen.image-storage.bucket", () -> BUCKET);
        registry.add("foremen.image-storage.cdn-base", () -> CDN_BASE);
        registry.add("foremen.image-storage.credentials.location", () -> "application-default");
        registry.add("foremen.image-storage.max-upload-size", () -> MAX_UPLOAD_BYTES + "B");
        // Let a large multipart body reach the handler (rather than being rejected by the servlet)
        // so GcsImageStorage.store performs the size check and returns the 400 under test.
        registry.add("spring.servlet.multipart.max-file-size", () -> "10MB");
        registry.add("spring.servlet.multipart.max-request-size", () -> "10MB");
    }

    /** Per-run-unique suffix so generated keys/codes never collide across runs (repeatability). */
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ImageStorage imageStorage;
    @Autowired
    private FinishingMaterialService finishingMaterialService;
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
    private ResourceDao resourceDao;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Storage gcsMock;

    @BeforeEach
    void setUp() {
        if (!(imageStorage instanceof GcsImageStorage)) {
            throw new IllegalStateException(
                    "Expected GcsImageStorage to be the active ImageStorage bean when image storage "
                            + "is configured, but got " + imageStorage.getClass().getName());
        }
        // Replace the private GCS client with a mock so store(...) does not hit the network.
        gcsMock = Mockito.mock(Storage.class);
        ReflectionTestUtils.setField(imageStorage, "storage", gcsMock);
        // Liquibase is disabled in the integration-test profile (schema from JPA entities), so the
        // seeded resource row is absent; insert the one ImageController validates against.
        seedResource("MATERIALS_FINISHING");
    }

    private void seedResource(String code) {
        transactionTemplate.executeWithoutResult(status -> {
            if (resourceDao.existsByCode(code)) {
                return;
            }
            ResourceEntity resource = new ResourceEntity();
            resource.setCode(code);
            resource.setNameRU(code);
            resource.setNamePL(code);
            resource.setDescriptionRU(code);
            resource.setDescriptionPL(code);
            entityManager.persist(resource);
            entityManager.flush();
        });
    }

    // ==========================================================================================
    // Photo object-key round-trip → resolved photoUrl on read/list (Requirements 8.6, 4.4)
    // ==========================================================================================

    @Nested
    @DisplayName("photo object-key round-trip (Requirements 8.6, 4.4)")
    class PhotoRoundTrip {

        @Test
        @DisplayName("a stored photo object key resolves to cdnBase + \"/\" + key on both read and list")
        void storedPhotoKeyResolvesToCdnUrlOnReadAndList() {
            String tag = uniqueTag();
            Refs refs = createReferences(tag);
            String objectKey = "finishing-materials/" + tag + "-photo.png";

            Long materialId = createMaterial(tag, refs, objectKey);

            try {
                // Single read (findByIdLocalized) exposes the resolved CDN photoUrl (Req 8.6/4.4).
                // Wrapped in a transaction so the read mapper can localize the lazy references
                // (mirrors the sibling cascade IT's transactional reads).
                String readUrl = transactionTemplate.execute(status ->
                        finishingMaterialService.findByIdLocalized(materialId).getPhotoUrl());
                assertThat(readUrl)
                        .as("read photoUrl is the CDN round-trip of the stored object key")
                        .isEqualTo(CDN_BASE + "/" + objectKey);

                // List read (find) exposes the same resolved CDN photoUrl for the row.
                String listUrl = transactionTemplate.execute(status -> {
                    Pageable pageable = PageRequest.of(0, 100);
                    return finishingMaterialService.find(pageable)
                            .getContent().stream()
                            .filter(m -> materialId.equals(m.getId()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("created material missing from list"))
                            .getPhotoUrl();
                });
                assertThat(listUrl)
                        .as("list photoUrl is the CDN round-trip of the stored object key")
                        .isEqualTo(CDN_BASE + "/" + objectKey);
            } finally {
                deleteMaterialsRaw(materialId);
                teardown(refs);
            }
        }

        @Test
        @DisplayName("a null photo object key resolves to a null photoUrl on read")
        void nullPhotoKeyResolvesToNullUrl() {
            String tag = uniqueTag();
            Refs refs = createReferences(tag);

            Long materialId = createMaterial(tag, refs, null);

            try {
                String readUrl = transactionTemplate.execute(status ->
                        finishingMaterialService.findByIdLocalized(materialId).getPhotoUrl());
                assertThat(readUrl)
                        .as("a finishing material with no photo resolves to a null photoUrl")
                        .isNull();
            } finally {
                deleteMaterialsRaw(materialId);
                teardown(refs);
            }
        }
    }

    // ==========================================================================================
    // Upload rejection reuse via the shared POST /api/images (Requirement 4.7)
    // ==========================================================================================

    @Nested
    @DisplayName("upload rejection reuse (Requirement 4.7)")
    class UploadRejection {

        @Test
        @DisplayName("POST /api/images - a non-image content type is rejected with 400 and stores nothing")
        void upload_nonImageContentType_returns400_storesNothing() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "notes.txt", MediaType.TEXT_PLAIN_VALUE,
                    "this is not an image".getBytes(StandardCharsets.UTF_8));

            mockMvc.perform(multipart("/api/images")
                            .file(file)
                            .param("entityKind", "finishing-materials")
                            .param("resource", "MATERIALS_FINISHING"))
                    .andExpect(status().isBadRequest());

            // Req 4.7: nothing stored — the mocked GCS client was never asked to create an object.
            Mockito.verify(gcsMock, Mockito.never())
                    .create(Mockito.any(BlobInfo.class), Mockito.any(byte[].class));
        }

        @Test
        @DisplayName("POST /api/images - an over-size image is rejected with 400 and stores nothing")
        void upload_overSizeImage_returns400_storesNothing() throws Exception {
            // A valid image content type, but a payload larger than the configured max-upload-size.
            byte[] tooBig = new byte[MAX_UPLOAD_BYTES + 1];
            MockMultipartFile file = new MockMultipartFile(
                    "file", "huge.png", MediaType.IMAGE_PNG_VALUE, tooBig);

            mockMvc.perform(multipart("/api/images")
                            .file(file)
                            .param("entityKind", "finishing-materials")
                            .param("resource", "MATERIALS_FINISHING"))
                    .andExpect(status().isBadRequest());

            // Req 4.7: nothing stored on an over-size upload.
            Mockito.verify(gcsMock, Mockito.never())
                    .create(Mockito.any(BlobInfo.class), Mockito.any(byte[].class));
        }
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    private String uniqueTag() {
        return "fmp-it-" + SEQ.incrementAndGet();
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

    /** Create a finishing material through the service, carrying the given (nullable) photo key. */
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

    /** Remove the created material row in its own transaction. */
    private void deleteMaterialsRaw(Long materialId) {
        transactionTemplate.executeWithoutResult(status -> {
            if (finishingMaterialDao.existsById(materialId)) {
                finishingMaterialDao.deleteById(materialId);
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
