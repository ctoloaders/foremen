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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the backend-mediated image upload rejection paths (FOR-04-17,
 * Requirement 12.8, covering 7.6/7.7).
 *
 * <p>Same boot as {@link ImageStorageUploadIntegrationTest}: {@code @SpringBootTest} + MockMvc over a
 * Testcontainers PostgreSQL, image storage CONFIGURED (bucket + cdn-base) so the concrete
 * {@link GcsImageStorage} is the active {@link ImageStorage}, a deliberately small
 * {@code max-upload-size} so the over-size boundary is reachable, and the seeded ABAC resource row
 * inserted by hand (Liquibase is disabled in the integration-test profile). The private GCS
 * {@code storage} field is replaced with a Mockito mock in {@code @BeforeEach} so a would-be upload
 * would call the mock — letting each rejection assert that <em>nothing was stored</em>
 * ({@code create} never invoked).
 *
 * <ul>
 *   <li>Req 7.6 — a non-image content type is rejected with 400 and stores nothing.</li>
 *   <li>Req 7.7 — an upload larger than the configured maximum is rejected with 400 and stores
 *       nothing.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class ImageStorageRejectionTest {

    /** Small enough that a modest payload exceeds it, exercising the over-size rejection (Req 7.7). */
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
        registry.add("foremen.image-storage.bucket", () -> "test-bucket");
        registry.add("foremen.image-storage.cdn-base", () -> "https://cdn.example.com/media");
        registry.add("foremen.image-storage.credentials.location", () -> "application-default");
        registry.add("foremen.image-storage.max-upload-size", () -> MAX_UPLOAD_BYTES + "B");
        // Let a large multipart body reach the handler (rather than being rejected by the servlet)
        // so GcsImageStorage.store performs the size check and returns the 400 under test.
        registry.add("spring.servlet.multipart.max-file-size", () -> "10MB");
        registry.add("spring.servlet.multipart.max-request-size", () -> "10MB");
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
        if (!(imageStorage instanceof GcsImageStorage)) {
            throw new IllegalStateException(
                    "Expected GcsImageStorage to be the active ImageStorage bean when image storage "
                            + "is configured, but got " + imageStorage.getClass().getName());
        }
        gcsMock = Mockito.mock(Storage.class);
        ReflectionTestUtils.setField(imageStorage, "storage", gcsMock);
        if (!resourceDao.existsByCode("MATERIALS_CONSTRUCTION")) {
            ResourceEntity resource = new ResourceEntity();
            resource.setCode("MATERIALS_CONSTRUCTION");
            resource.setNameRU("MATERIALS_CONSTRUCTION");
            resource.setNamePL("MATERIALS_CONSTRUCTION");
            resource.setDescriptionRU("MATERIALS_CONSTRUCTION");
            resource.setDescriptionPL("MATERIALS_CONSTRUCTION");
            entityManager.persist(resource);
            entityManager.flush();
        }
    }

    @Test
    @DisplayName("POST /api/images - a non-image content type is rejected with 400 and stores nothing")
    void upload_nonImageContentType_returns400_storesNothing() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", MediaType.TEXT_PLAIN_VALUE,
                "this is not an image".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/images")
                        .file(file)
                        .param("entityKind", "construction-materials")
                        .param("resource", "MATERIALS_CONSTRUCTION"))
                .andExpect(status().isBadRequest());

        // Req 7.6: nothing stored — the mocked GCS client was never asked to create an object.
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
                        .param("entityKind", "construction-materials")
                        .param("resource", "MATERIALS_CONSTRUCTION"))
                .andExpect(status().isBadRequest());

        // Req 7.7: nothing stored on an over-size upload.
        Mockito.verify(gcsMock, Mockito.never())
                .create(Mockito.any(BlobInfo.class), Mockito.any(byte[].class));
    }
}
