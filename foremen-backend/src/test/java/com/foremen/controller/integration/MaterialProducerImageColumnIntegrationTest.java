package com.foremen.controller.integration;

import com.foremen.dao.MaterialProducerDao;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.service.image.GcsImageStorage;
import com.foremen.service.image.ImageStorage;
import com.foremen.testsupport.MockMvcSecurityConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the additive {@code material_producers.image} column and its end-to-end image
 * round-trip (FOR-04-17, Requirement 12.10, covering Requirements 8.1–8.4).
 *
 * <p>The test verifies three things, mirroring the boot of the other FOR-04-17 controller ITs
 * ({@code @SpringBootTest} + MockMvc over a Testcontainers PostgreSQL, {@code integration-test}
 * profile with {@code ddl-auto: create-drop} so the schema is built from the JPA entities and
 * Liquibase is disabled):
 *
 * <ol>
 *   <li><b>Column present</b> — the additive nullable {@code material_producers.image VARCHAR(512)}
 *       column exists. In this profile the column comes from the {@link MaterialProducerEntity}
 *       {@code image} field (Liquibase is off), and its presence/type/nullability are asserted via
 *       {@code information_schema.columns} (Requirement 8.1).</li>
 *   <li><b>Round-trip</b> — a producer's {@code image} object key is persisted via the DAO and, when
 *       read/created through the API, the DTO exposes the resolved CDN URL
 *       {@code cdnBase + "/" + objectKey} (Requirement 8.4). Image storage is CONFIGURED (bucket +
 *       cdn-base) so the concrete {@link GcsImageStorage} is the active {@link ImageStorage} and
 *       resolves the URL; no network call is made because {@code toCdnUrl} is a pure string
 *       operation over the key.</li>
 *   <li><b>Changeset idempotent</b> — the additive changeset {@code 059-add-material-producers-image}
 *       is proven idempotent against a real database in the {@link LiquibaseIdempotencyIT} nested
 *       class, which applies the full Liquibase changelog twice against its own fresh Testcontainers
 *       PostgreSQL (Liquibase is disabled in the {@code integration-test} profile, so idempotency is
 *       exercised through a raw Liquibase harness, as {@code AuthMigrationIntegrationTest} does):
 *       after the first run the {@code image} column exists, and the second run — guarded by the
 *       {@code columnExists}/{@code onFail="MARK_RAN"} precondition — adds no changesets and leaves
 *       exactly one {@code image} column (Requirements 8.2, 8.3).</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class MaterialProducerImageColumnIntegrationTest {

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
        // bucket + cdn-base present so toCdnUrl resolves cdnBase + "/" + key. The credentials use the
        // application-default sentinel so building the client in @PostConstruct performs no
        // network/credential resolution; the read path never calls the GCS client.
        registry.add("foremen.image-storage.bucket", () -> "test-bucket");
        registry.add("foremen.image-storage.cdn-base", () -> CDN_BASE);
        registry.add("foremen.image-storage.credentials.location", () -> "application-default");
    }

    /** Per-run unique code suffix so scenarios are repeatable even without rollback. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MaterialProducerDao materialProducerDao;

    @Autowired
    private ImageStorage imageStorage;

    @PersistenceContext
    private EntityManager entityManager;

    private String uniqueCode() {
        return "mpimg" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    // --- Column present (Requirement 8.1) ---

    @Test
    @DisplayName("material_producers.image column is present, VARCHAR(512), nullable")
    void producerImageColumn_isPresentNullableVarchar512() {
        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT data_type, character_maximum_length, is_nullable "
                                + "FROM information_schema.columns "
                                + "WHERE table_schema = 'public' AND table_name = 'material_producers' "
                                + "AND column_name = 'image'")
                .getSingleResult();

        assertThat(row).as("material_producers.image column should exist").isNotNull();
        assertThat(((String) row[0]))
                .as("material_producers.image data_type")
                .isEqualTo("character varying");
        assertThat(((Number) row[1]).intValue())
                .as("material_producers.image length")
                .isEqualTo(512);
        assertThat("YES".equalsIgnoreCase((String) row[2]))
                .as("material_producers.image should be nullable")
                .isTrue();
    }

    // --- Round-trip: object key persisted -> DTO exposes resolved CDN URL (Requirement 8.4) ---

    @Test
    @DisplayName("A producer's stored image object key resolves to cdnBase + '/' + key on read")
    void producerWithImageKey_readExposesResolvedCdnUrl() throws Exception {
        // Sanity: image storage is configured, so the resolver builds a real CDN URL.
        assertThat(imageStorage.isConfigured())
                .as("image storage should be configured so imageUrl resolves").isTrue();

        String code = uniqueCode();
        String objectKey = "producers/" + java.util.UUID.randomUUID() + ".png";

        MaterialProducerEntity producer = new MaterialProducerEntity();
        producer.setCode(code);
        producer.setNameRU("Egger");
        producer.setNamePL("Egger");
        producer.setActive(true);
        producer.setImage(objectKey);
        materialProducerDao.save(producer);
        entityManager.flush();

        // The stored object key resolves to the CDN URL via ImageStorage.toCdnUrl(...).
        assertThat(imageStorage.toCdnUrl(objectKey)).isEqualTo(CDN_BASE + "/" + objectKey);

        // Read DTO exposes the resolved imageUrl (never the raw object key).
        mockMvc.perform(get("/api/material-producers/" + producer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.imageUrl").value(CDN_BASE + "/" + objectKey));

        // The list DTO exposes the same resolved imageUrl.
        mockMvc.perform(get("/api/material-producers")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].imageUrl")
                        .value(contains(CDN_BASE + "/" + objectKey)));
    }

    @Test
    @DisplayName("Creating a producer with an image object key round-trips to the resolved CDN URL")
    void createProducerWithImage_roundTripsResolvedCdnUrl() throws Exception {
        String code = uniqueCode();
        String objectKey = "producers/" + java.util.UUID.randomUUID() + ".jpg";

        // Create with an image object key -> response exposes the resolved CDN URL.
        mockMvc.perform(post("/api/material-producers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Grohe",
                                    "namePL": "Grohe",
                                    "active": true,
                                    "image": "%s"
                                }
                                """.formatted(code, objectKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.imageUrl").value(CDN_BASE + "/" + objectKey));

        // A fresh read confirms the persisted object key (not the CDN URL) is what was stored, and
        // still resolves to the CDN URL on read.
        entityManager.clear();
        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT id, image FROM material_producers WHERE code = :code")
                .setParameter("code", code)
                .getSingleResult();
        Long id = ((Number) row[0]).longValue();
        String storedImage = (String) row[1];
        assertThat(storedImage)
                .as("the raw GCS object key is persisted, not the CDN URL")
                .isEqualTo(objectKey);

        mockMvc.perform(get("/api/material-producers/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(CDN_BASE + "/" + objectKey));
    }

    @Test
    @DisplayName("A producer with a null image key resolves to a null imageUrl (entities keep working without images)")
    void producerWithoutImage_readExposesNullImageUrl() throws Exception {
        String code = uniqueCode();
        MaterialProducerEntity producer = new MaterialProducerEntity();
        producer.setCode(code);
        producer.setNameRU("Porta");
        producer.setNamePL("Porta");
        producer.setActive(true);
        // image intentionally left null
        materialProducerDao.save(producer);
        entityManager.flush();

        assertThat(imageStorage.toCdnUrl(null)).as("null key -> null URL").isNull();

        mockMvc.perform(get("/api/material-producers/" + producer.getId())
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.imageUrl").doesNotExist());
    }

    /**
     * Proves changeset {@code 059-add-material-producers-image} is idempotent against a REAL database
     * (Requirements 8.2, 8.3). Because the {@code integration-test} profile disables Liquibase
     * ({@code create-drop} builds the schema from entities), idempotency is exercised via a raw
     * Liquibase harness against a dedicated fresh PostgreSQL container — the same technique
     * {@code AuthMigrationIntegrationTest} uses. Applying the full changelog once creates the
     * {@code material_producers.image} column; a second application, guarded by the
     * {@code columnExists}/{@code onFail="MARK_RAN"} precondition, records no new changeset and leaves
     * exactly one {@code image} column.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class LiquibaseIdempotencyIT {

        private static final String CHANGELOG = "database_files/changelog.xml";

        private PostgreSQLContainer<?> db;

        @BeforeAll
        void startContainer() {
            db = new PostgreSQLContainer<>("postgres:16-alpine");
            db.start();
        }

        @AfterAll
        void stopContainer() {
            if (db != null) {
                db.stop();
            }
        }

        private Connection newConnection() throws Exception {
            return DriverManager.getConnection(
                    db.getJdbcUrl(), db.getUsername(), db.getPassword());
        }

        private void runLiquibase() throws Exception {
            try (Connection connection = newConnection()) {
                Database database = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(connection));
                try (Liquibase liquibase = new Liquibase(
                        CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                    liquibase.update(new Contexts());
                }
            }
        }

        @Test
        @DisplayName("Applying the changelog adds material_producers.image; re-applying changeset 059 is a no-op")
        void additiveImageColumn_isCreatedAndIdempotent() throws Exception {
            // First application: full migration creates the additive image column.
            runLiquibase();

            assertColumnExistsNullableVarchar512();
            int changeSetsAfterFirstRun = countAppliedChangeSets();
            assertThat(changeSetsAfterFirstRun)
                    .as("changelog should record all executed/marked changesets")
                    .isPositive();

            // Second application: the columnExists / MARK_RAN precondition on changeset 059 (and the
            // rest of the changelog) makes it a no-op.
            runLiquibase();

            assertColumnExistsNullableVarchar512();
            assertThat(countAppliedChangeSets())
                    .as("re-running the changelog must not add or duplicate changesets")
                    .isEqualTo(changeSetsAfterFirstRun);
            assertThat(countColumn("material_producers", "image"))
                    .as("exactly one material_producers.image column after the idempotent re-run")
                    .isEqualTo(1);
        }

        private void assertColumnExistsNullableVarchar512() throws Exception {
            String sql = "SELECT data_type, character_maximum_length, is_nullable "
                    + "FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = 'material_producers' "
                    + "AND column_name = 'image'";
            try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("material_producers.image column should exist after migration").isTrue();
                assertThat(rs.getString("data_type"))
                        .as("material_producers.image data_type").isEqualTo("character varying");
                assertThat(rs.getInt("character_maximum_length"))
                        .as("material_producers.image length").isEqualTo(512);
                assertThat("YES".equalsIgnoreCase(rs.getString("is_nullable")))
                        .as("material_producers.image should be nullable").isTrue();
            }
        }

        private int countColumn(String table, String column) throws Exception {
            String sql = "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
            try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, table);
                ps.setString(2, column);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }

        private int countAppliedChangeSets() throws Exception {
            String sql = "SELECT COUNT(*) FROM databasechangelog";
            try (Connection c = newConnection();
                 PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
