package com.foremen.controller.integration;

import com.foremen.dao.ResourceDao;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.service.image.GcsImageStorage;
import com.foremen.service.image.ImageStorage;
import com.foremen.testsupport.MockMvcSecurityConfig;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the backend-mediated image upload happy path (FOR-04-17, Requirement 12.7,
 * covering 7.2/7.3/7.4/7.5).
 *
 * <p>Boots {@code @SpringBootTest} + MockMvc against a Testcontainers PostgreSQL (a DB is required
 * for context startup, mirroring the other controller ITs). Image storage is CONFIGURED here — a
 * {@code bucket} and a {@code cdn-base} are supplied via {@link DynamicPropertySource}, so the
 * concrete {@link GcsImageStorage} bean is the active {@link ImageStorage} (rather than the disabled
 * fallback), and a small {@code max-upload-size} keeps the boundary within reach of the rejection
 * sibling test. Because the integration-test profile disables Liquibase and creates the schema from
 * the JPA entities ({@code ddl-auto: create-drop}), the seeded ABAC resource rows are absent, so the
 * test inserts the {@code MATERIALS_CONSTRUCTION} resource row that {@code ImageController}
 * validates the dynamic {@code resource} param against.
 *
 * <p>Since real Google Cloud Storage is not available in the test, the private {@code storage} field
 * of the {@code GcsImageStorage} bean (normally built in a {@code @PostConstruct}) is replaced in
 * {@code @BeforeEach} with a Mockito mock via {@link ReflectionTestUtils} — the same least-invasive
 * technique {@code ImageUrlResolutionPropertyTest} uses — so {@code store} builds a namespaced key
 * and calls {@code create(...)} on the mock instead of hitting the network.
 *
 * <p>The upload asserts the response {@code {objectKey, imageUrl}} where the key begins with the
 * requested per-entity-kind namespace and {@code imageUrl == cdnBase + "/" + objectKey}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class ImageStorageUploadIntegrationTest {

    private static final String CDN_BASE = "https://cdn.example.com/media";

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
        // Configure image storage so GcsImageStorage is the active ImageStorage bean (Req 7.11):
        // bucket + cdn-base present. Credentials use the application-default sentinel so building
        // the client in @PostConstruct performs no network/credential resolution; the client is
        // replaced with a mock in @BeforeEach anyway. A small max-upload-size makes the boundary
        // reachable for the rejection sibling.
        registry.add("foremen.image-storage.bucket", () -> "test-bucket");
        registry.add("foremen.image-storage.cdn-base", () -> CDN_BASE);
        registry.add("foremen.image-storage.credentials.location", () -> "application-default");
        registry.add("foremen.image-storage.max-upload-size", () -> "64KB");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ImageStorage imageStorage;

    @Autowired
    private ResourceDao resourceDao;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private Storage gcsMock;

    @BeforeEach
    void setUp() {
        // Image storage is configured, so the active bean is the concrete GcsImageStorage.
        assertGcsActive();
        // Replace the private GCS client with a mock so store(...) does not hit the network.
        gcsMock = Mockito.mock(Storage.class);
        ReflectionTestUtils.setField(imageStorage, "storage", gcsMock);
        // The integration-test profile builds the schema from entities (Liquibase disabled), so the
        // seeded resource rows are absent; insert the one ImageController validates against.
        seedResource("MATERIALS_CONSTRUCTION");
    }

    private void assertGcsActive() {
        if (!(imageStorage instanceof GcsImageStorage)) {
            throw new IllegalStateException(
                    "Expected GcsImageStorage to be the active ImageStorage bean when image storage "
                            + "is configured, but got " + imageStorage.getClass().getName());
        }
    }

    private void seedResource(String code) {
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
    }

    @Test
    @DisplayName("POST /api/images - a valid PNG stores under the entity-kind namespace and resolves the CDN URL")
    void upload_validPng_storesNamespacedKeyAndResolvesCdnUrl() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", MediaType.IMAGE_PNG_VALUE,
                "fake-png-bytes".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/images")
                        .file(file)
                        .param("entityKind", "construction-materials")
                        .param("resource", "MATERIALS_CONSTRUCTION"))
                .andExpect(status().isOk())
                // Req 7.3/7.5: object key is namespaced per entity kind: "{namespace}/{uuid}.png".
                .andExpect(jsonPath("$.objectKey")
                        .value(startsWith("construction-materials/")))
                .andExpect(jsonPath("$.objectKey")
                        .value(matchesPattern("construction-materials/[0-9a-fA-F-]+\\.png")))
                // Req 7.4: imageUrl == cdnBase + "/" + objectKey (resolved at read time).
                .andExpect(jsonPath("$.imageUrl")
                        .value(startsWith(CDN_BASE + "/construction-materials/")))
                .andExpect(jsonPath("$.imageUrl")
                        .value(matchesPattern("\\Q" + CDN_BASE + "\\E/construction-materials/[0-9a-fA-F-]+\\.png")));

        // No real upload: the mocked GCS client received a create(...) with the namespaced blob.
        Mockito.verify(gcsMock).create(Mockito.any(BlobInfo.class), Mockito.any(byte[].class));
    }

    @Test
    @DisplayName("POST /api/images - a valid JPEG for the producers namespace stores under producers/")
    void upload_validJpegForProducers_storesUnderProducersNamespace() throws Exception {
        seedResource("MATERIAL_PRODUCERS");

        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.jpg", MediaType.IMAGE_JPEG_VALUE,
                "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/images")
                        .file(file)
                        .param("entityKind", "producers")
                        .param("resource", "MATERIAL_PRODUCERS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey")
                        .value(matchesPattern("producers/[0-9a-fA-F-]+\\.jpg")))
                .andExpect(jsonPath("$.imageUrl")
                        .value(matchesPattern("\\Q" + CDN_BASE + "\\E/producers/[0-9a-fA-F-]+\\.jpg")));

        Mockito.verify(gcsMock).create(Mockito.any(BlobInfo.class), Mockito.any(byte[].class));
    }
}
