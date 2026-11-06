package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.NotificationDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.NotificationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.roomsimport.ai.DetectRequest;
import com.foremen.service.roomsimport.ai.DetectResponse;
import com.foremen.service.roomsimport.ai.MeasureRequest;
import com.foremen.service.roomsimport.ai.MeasureResponse;
import com.foremen.service.roomsimport.ai.RoomsVisionClient;
import com.foremen.service.roomsimport.ai.StubRoomsVisionClient;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-05-02 task 4.5 - shared plumbing of the rooms auto-import integration tests: the real Liquibase
 * schema (changesets 213-215 and the {@code ROOMS} permission seeds included) on one Testcontainers
 * PostgreSQL shared by the subclasses, the deterministic {@code StubRoomsVisionClient} (wrapped so the
 * tests can count AI calls), and an in-memory {@link ImageStorage} (object storage is not available in
 * tests; it can be switched to "not configured").
 *
 * <p>The suite is deliberately <b>not</b> transactional: the import runs on an async worker thread and
 * every HTTP call commits for real.
 *
 * <p>Repeatability: every user, project and prompt carries a per-test-instance {@code runId}; nothing is
 * shared between runs and the container is fresh per JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false",
        // The deployed app runs with Spring's default open-in-view=true (the integration-test profile
        // switches it off); the paginated room list maps lazy project / room-type references in the
        // controller layer and relies on it, exactly as in production.
        "spring.jpa.open-in-view=true",
        "foremen.bank-feed.stub.enabled=true",
        "foremen.public-base-url=http://localhost:3000",
        "foremen.rooms-import.enabled=true",
        "foremen.rooms-import.llm.stub=true",
        // One measuring call at a time, so "no AI call after cancel" is observable deterministically.
        "foremen.rooms-import.max-concurrency=1"
})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Import(RoomsAutoimportItBase.ItConfig.class)
abstract class RoomsAutoimportItBase {

    /** One container per JVM, cleaned up by Testcontainers' resource reaper. */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");
    private static boolean migrated;

    /** Prompts of every AI call (detect + measure) made through the stub, for call-count assertions. */
    static final Collection<String> AI_CALLS = new ConcurrentLinkedQueue<>();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        synchronized (RoomsAutoimportItBase.class) {
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

    @Autowired protected ImageStorage imageStorage;
    /** The in-memory object store of this test's context. */
    protected MemoryImageStorage storage;
    @Autowired protected MockMvc mockMvc;
    @Autowired protected JwtTokenProvider jwt;
    @Autowired protected UserDao userDao;
    @Autowired protected RoleDao roleDao;
    @Autowired protected ProjectDao projectDao;
    @Autowired protected ProjectMemberDao projectMemberDao;
    @Autowired protected RoomDao roomDao;
    @Autowired protected RoomTypeDao roomTypeDao;
    @Autowired protected NotificationDao notificationDao;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected ProjectAccessCache projectAccessCache;
    @Autowired protected JdbcTemplate jdbc;
    @PersistenceContext protected EntityManager entityManager;

    protected final String runId = UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
    private final Map<String, UserEntity> users = new ConcurrentHashMap<>();

    @BeforeEach
    void storageConfigured() {
        storage = (MemoryImageStorage) imageStorage;
        storage.setConfigured(true);
    }

    // ---------------------------------------------------------------- test configuration

    @TestConfiguration(proxyBeanMethods = false)
    static class ItConfig {

        @Bean
        @Primary
        ImageStorage memoryImageStorage() {
            return new MemoryImageStorage();
        }

        /** Wraps the stub vision client so every call is recorded in {@link #AI_CALLS}. */
        @Bean
        static BeanPostProcessor countingVisionClientPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof StubRoomsVisionClient stub) {
                        return new RoomsVisionClient() {
                            @Override
                            public boolean isEnabled() {
                                return stub.isEnabled();
                            }

                            @Override
                            public DetectResponse detectRooms(DetectRequest request) {
                                AI_CALLS.add("detect|" + request.userPrompt());
                                return stub.detectRooms(request);
                            }

                            @Override
                            public MeasureResponse measureRoom(MeasureRequest request) {
                                AI_CALLS.add("measure|" + request.userPrompt());
                                return stub.measureRoom(request);
                            }
                        };
                    }
                    return bean;
                }
            };
        }
    }

    /** In-memory {@link ImageStorage} with prefix listing, real deletes and a "configured" switch. */
    static final class MemoryImageStorage implements ImageStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private volatile boolean configured = true;

        void setConfigured(boolean configured) {
            this.configured = configured;
        }

        Set<String> keysWithPrefix(String prefix) {
            return objects.keySet().stream().filter(k -> k.startsWith(prefix)).collect(Collectors.toSet());
        }

        boolean contains(String key) {
            return objects.containsKey(key);
        }

        @Override
        public String store(MultipartFile file, String keyNamespace) {
            return storeObject(file, keyNamespace);
        }

        @Override
        public String storeObject(MultipartFile file, String keyNamespace) {
            if (!configured) {
                throw new IllegalStateException("storage not configured");
            }
            String key = (keyNamespace == null ? "" : keyNamespace.strip()) + "/" + UUID.randomUUID() + "."
                    + extensionOf(file.getContentType());
            try {
                objects.put(key, file.getBytes());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return key;
        }

        private static String extensionOf(String contentType) {
            if (contentType == null) {
                return "bin";
            }
            return switch (contentType.toLowerCase(Locale.ROOT)) {
                case "image/png" -> "png";
                case "image/jpeg" -> "jpg";
                case "image/webp" -> "webp";
                case "application/pdf" -> "pdf";
                default -> "bin";
            };
        }

        @Override
        public String toCdnUrl(String objectKey) {
            return objectKey == null ? null : "https://cdn.test/" + objectKey;
        }

        @Override
        public void deleteIfOrphan(String objectKey) {
            if (objectKey != null) {
                objects.remove(objectKey);
            }
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public byte[] readObject(String objectKey) {
            if (!configured || objectKey == null || objectKey.isBlank()) {
                return null;
            }
            byte[] bytes = objects.get(objectKey);
            if (bytes == null) {
                throw new IllegalArgumentException("No object " + objectKey);
            }
            return bytes;
        }

        @Override
        public String signedDownloadUrl(String objectKey, long ttlSeconds) {
            return objectKey == null ? null : "https://signed.test/" + objectKey;
        }

        @Override
        public List<StoredObject> listObjects(String prefix) {
            return objects.keySet().stream().filter(k -> k.startsWith(prefix))
                    .map(k -> new StoredObject(k, java.time.Instant.now().minusSeconds(86_400))).toList();
        }
    }

    // ---------------------------------------------------------------- seeding

    protected TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** A real user of the seeded role (created once per role and test instance). */
    protected UserEntity user(String roleCode) {
        return users.computeIfAbsent(roleCode, code -> tx().execute(s -> {
            UserEntity u = new UserEntity();
            u.setName("RoomsIT " + code + " " + runId);
            u.setEmail("rooms-it-" + code.toLowerCase(Locale.ROOT) + "+" + runId + "@example.com");
            u.setRole(roleDao.findByCode(code).orElseThrow());
            u.setActive(true);
            return userDao.save(u);
        }));
    }

    protected String bearer(String roleCode) {
        UserEntity u = user(roleCode);
        return "Bearer " + jwt.generateAccessToken(u.getId(), roleCode, u.getEmail());
    }

    protected long createProject(String label) {
        return tx().execute(s -> {
            ProjectEntity p = new ProjectEntity();
            p.setName("RoomsIT " + label + " " + runId + "-" + UUID.randomUUID());
            p.setStatus(ProjectStatus.DRAFT);
            return projectDao.save(p).getId();
        });
    }

    protected void addMember(String roleCode, long projectId) {
        UserEntity u = user(roleCode);
        tx().executeWithoutResult(s -> {
            ProjectMemberEntity m = new ProjectMemberEntity();
            m.setUser(userDao.findById(u.getId()).orElseThrow());
            m.setProjectId(projectId);
            m.setProjectRole(roleDao.findByCode(roleCode).orElseThrow());
            projectMemberDao.save(m);
        });
        projectAccessCache.invalidate(u.getId());
    }

    protected long anyRoomTypeId() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM room_types ORDER BY id LIMIT 1", Long.class);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        return tx().execute(s -> {
            RoomTypeEntity rt = new RoomTypeEntity();
            rt.setCode("RT-" + UUID.randomUUID());
            rt.setNameRU("Тип");
            rt.setNamePL("Typ");
            rt.setActive(true);
            return roomTypeDao.save(rt).getId();
        });
    }

    /** A pre-existing manual room, persisted directly. */
    protected long seedRoom(long projectId, String label, String floorArea) {
        long typeId = anyRoomTypeId();
        return tx().execute(s -> {
            RoomEntity room = new RoomEntity();
            room.setProject(projectDao.findById(projectId).orElseThrow());
            room.setRoomType(roomTypeDao.findById(typeId).orElseThrow());
            room.setLabel(label);
            room.setFloorArea(new java.math.BigDecimal(floorArea));
            room.setFloorAreaSource(MeasureSource.MANUAL);
            return roomDao.save(room).getId();
        });
    }

    // ---------------------------------------------------------------- import API helpers

    /** A prompt that carries the run id (so AI calls can be attributed to this test) plus the markers. */
    protected String prompt(String markers) {
        return markers + " run-" + runId;
    }

    protected MvcResult start(long projectId, String bearer, String prompt, MockMultipartFile... files)
            throws Exception {
        MockMultipartHttpServletRequestBuilder b = multipart("/api/rooms/project/" + projectId + "/autoimport");
        if (prompt != null) {
            b.param("prompt", prompt);
        }
        for (MockMultipartFile f : files) {
            b.file(f);
        }
        if (bearer != null) {
            b.header("Authorization", bearer);
        }
        return mockMvc.perform(b).andReturn();
    }

    /** Starts a job as ADMIN and asserts {@code 202}; returns the job id. */
    protected long startOk(long projectId, String prompt, MockMultipartFile... files) throws Exception {
        MvcResult r = start(projectId, bearer("ADMIN"), prompt, files);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(202);
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.jobId")).longValue();
    }

    protected MvcResult getJob(long jobId, String bearer) throws Exception {
        return mockMvc.perform(get("/api/rooms/autoimport/" + jobId).header("Authorization", bearer)).andReturn();
    }

    protected MvcResult current(long projectId, String bearer) throws Exception {
        return mockMvc.perform(get("/api/rooms/project/" + projectId + "/autoimport/current")
                .header("Authorization", bearer)).andReturn();
    }

    protected MvcResult cancel(long jobId, String bearer) throws Exception {
        return mockMvc.perform(post("/api/rooms/autoimport/" + jobId + "/cancel")
                .header("Authorization", bearer)).andReturn();
    }

    protected MvcResult discard(long jobId, String bearer) throws Exception {
        return mockMvc.perform(delete("/api/rooms/autoimport/" + jobId).header("Authorization", bearer))
                .andReturn();
    }

    /** Polls the job until its status is one of {@code statuses}; returns the last JSON body. */
    protected String awaitStatus(long jobId, String... statuses) throws Exception {
        Set<String> wanted = Set.of(statuses);
        String[] last = new String[1];
        await("job " + jobId + " -> " + wanted, () -> {
            try {
                MvcResult r = getJob(jobId, bearer("ADMIN"));
                if (r.getResponse().getStatus() != 200) {
                    return false;
                }
                last[0] = r.getResponse().getContentAsString();
                return wanted.contains(JsonPath.<String>read(last[0], "$.status"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, 60_000);
        return last[0];
    }

    protected String awaitTerminal(long jobId) throws Exception {
        return awaitStatus(jobId, "COMPLETED", "FAILED");
    }

    protected static void await(String what, BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    protected long aiCalls(String kind) {
        return AI_CALLS.stream().filter(c -> c.startsWith(kind + "|") && c.contains("run-" + runId)).count();
    }

    protected List<NotificationEntity> notifications(String roleCode, String type) {
        return notificationDao.findByRecipientIdOrderByCreatedDateDesc(user(roleCode).getId()).stream()
                .filter(n -> type == null || type.equals(n.getType())).toList();
    }

    protected void awaitNotifications(String roleCode, String type, int count) throws InterruptedException {
        await(count + " notification(s) " + type, () -> notifications(roleCode, type).size() >= count, 15_000);
    }

    // ---------------------------------------------------------------- save / room helpers

    /** The ready-to-save {@code RoomCreateRequest} of draft room {@code index}, JSON-ready. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> draftRoom(String jobJson, int index) {
        Map<String, Object> room = new java.util.LinkedHashMap<>((Map<String, Object>) JsonPath.read(jobJson,
                "$.result.rooms[" + index + "].room"));
        if (room.get("roomTypeId") == null) {
            // an unresolved type is flagged by the draft; the user picks one before saving
            room.put("roomTypeId", anyRoomTypeId());
        }
        return room;
    }

    protected MvcResult save(long projectId, String bearer, List<Map<String, Object>> creates) throws Exception {
        String body = JsonPath.parse(Map.of("createList", creates)).jsonString();
        return mockMvc.perform(post("/api/rooms/project/" + projectId + "/save")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    protected Map<String, Object> mediaRef(long jobId, String mediaId) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("jobId", jobId);
        m.put("mediaId", mediaId);
        return m;
    }

    /** A decodable PNG, big enough for the stub's normalised crop boxes. */
    protected static byte[] pngBytes() {
        try {
            BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 400; x++) {
                for (int y = 0; y < 300; y++) {
                    img.setRGB(x, y, (x * 7 + y * 13) & 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    protected static MockMultipartFile png(String partName, String fileName) {
        return new MockMultipartFile(partName, fileName, "image/png", pngBytes());
    }
}
