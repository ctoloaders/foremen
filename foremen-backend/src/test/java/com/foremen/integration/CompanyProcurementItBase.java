package com.foremen.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.image.ImageStorage;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-07-03 task 5.3 - shared plumbing of the controller integration tests of the company-procurement
 * endpoints: the real Liquibase schema (changesets 207-210 included) on one Testcontainers PostgreSQL
 * shared by the subclasses, the {@code integration-test} profile (Hibernate {@code ddl-auto = none}),
 * a mocked {@link ImageStorage} (object storage is not available in tests) and small seeding helpers.
 *
 * <p>Repeatability: every user, project and document carries a per-run id, nothing is shared between
 * runs, and the container is fresh per JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false",
        "foremen.bank-feed.stub.enabled=true",
        "foremen.public-base-url=http://localhost:3000"
})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
abstract class CompanyProcurementItBase {

    /** One container per JVM, cleaned up by Testcontainers' resource reaper. */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");
    private static boolean migrated;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        synchronized (CompanyProcurementItBase.class) {
            if (!POSTGRES.isRunning()) {
                POSTGRES.start();
            }
            if (!migrated) {
                BankLedgerItSupport.migrate(POSTGRES);
                migrated = true;
            }
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JwtTokenProvider jwt;
    @Autowired protected UserDao userDao;
    @Autowired protected RoleDao roleDao;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected ProjectAccessCache projectAccessCache;
    @PersistenceContext protected EntityManager entityManager;

    @MockitoBean protected ImageStorage imageStorage;

    protected final String runId = UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
    private final Map<String, UserEntity> users = new LinkedHashMap<>();

    @BeforeEach
    void stubObjectStorage() {
        when(imageStorage.isConfigured()).thenReturn(true);
        when(imageStorage.storeObject(any(), anyString())).thenAnswer(inv -> "it/" + UUID.randomUUID());
        when(imageStorage.signedDownloadUrl(anyString(), anyLong())).thenReturn("https://signed.example/doc");
    }

    protected TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** A real user of the seeded role (created once per role and test instance). */
    protected UserEntity user(String roleCode) {
        return users.computeIfAbsent(roleCode, code -> tx().execute(s -> {
            UserEntity u = new UserEntity();
            u.setName("ProcIT " + code + " " + runId);
            u.setEmail("proc-it-" + code.toLowerCase(Locale.ROOT) + "+" + runId + "@example.com");
            u.setRole(roleDao.findByCode(code).orElseThrow());
            u.setActive(true);
            return userDao.save(u);
        }));
    }

    protected String bearer(String roleCode) {
        UserEntity u = user(roleCode);
        return "Bearer " + jwt.generateAccessToken(u.getId(), roleCode, u.getEmail());
    }

    /** An {@code ACTIVE} project with a per-run name. */
    protected long createActiveProject(String label) {
        return tx().execute(s -> {
            ProjectEntity p = new ProjectEntity();
            p.setName("ProcIT " + label + " " + runId);
            p.setStatus(ProjectStatus.ACTIVE);
            entityManager.persist(p);
            entityManager.flush();
            return p.getId();
        });
    }

    /** Makes the user of {@code roleCode} a member of the project. */
    protected void addMember(String roleCode, long projectId) {
        UserEntity u = user(roleCode);
        tx().executeWithoutResult(s -> {
            ProjectMemberEntity m = new ProjectMemberEntity();
            m.setUser(entityManager.getReference(UserEntity.class, u.getId()));
            m.setProjectId(projectId);
            m.setProjectRole(roleDao.findByCode(roleCode).orElseThrow());
            entityManager.persist(m);
        });
        projectAccessCache.invalidate(u.getId());
    }

    /** A {@code PARSED} document with one unmatched line; {@code projectId == null} = no project. */
    protected long createDocument(Long projectId, String uploaderRole, String supplier) {
        UserEntity uploader = user(uploaderRole);
        return tx().execute(s -> {
            ProcurementDocumentEntity d = new ProcurementDocumentEntity();
            if (projectId != null) {
                d.setProject(entityManager.getReference(ProjectEntity.class, projectId));
            }
            d.setUploadedBy(entityManager.getReference(UserEntity.class, uploader.getId()));
            d.setRawObjectKey("it/raw-" + UUID.randomUUID());
            d.setSourceMimeType("image/png");
            d.setStatus(ProcessingStatus.PARSED);
            d.setSupplierName(supplier);
            d.setUploadedAt(OffsetDateTime.now());
            entityManager.persist(d);
            ProcurementLineEntity line = new ProcurementLineEntity();
            line.setDocument(d);
            line.setLineNo(1);
            line.setRawName("Item " + supplier);
            line.setMatchTier(MatchTier.NONE);
            line.setMatchStatus(MatchStatus.UNMATCHED);
            entityManager.persist(line);
            entityManager.flush();
            return d.getId();
        });
    }
}
