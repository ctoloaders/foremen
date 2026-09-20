package com.foremen.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end CRUD + write-validation + audit-snapshot integration test for the FOR-04-19
 * work-material-consumption controller (task 12.1, Requirements 2.1, 3.1, 3.8, 10.1).
 *
 * <p>Against the real read/write surface ({@code POST}/{@code GET}/{@code PUT}/{@code DELETE}
 * {@code /api/work-material-consumptions}) this test asserts:
 * <ul>
 *   <li><b>CRUD (Requirement 3.1):</b> create with all required references echoes the fields; the row
 *       reads back via {@code GET /{id}}; an update mutates it; a delete removes it and a subsequent
 *       read is {@code 404};</li>
 *   <li><b>Write validation (Requirements 3.2–3.6):</b> a missing required field (no {@code workItemId},
 *       no {@code branch}, no {@code normQty}), the XOR + branch-match rule (NEITHER / BOTH type ids,
 *       type not matching branch), the {@code normQty} range boundaries (negative, over-max), and a
 *       dangling reference id are all rejected {@code 4xx} with nothing persisted (Requirement 10.1);</li>
 *   <li><b>{@code justification} PL fallback (Requirement 3.8):</b> the read DTO exposes the single
 *       localized {@code justification} (PL for the default locale) while the extended DTO
 *       ({@code GET /{id}}) additionally carries BOTH raw {@code justificationRU}/{@code justificationPL}
 *       variants;</li>
 *   <li><b>Audit snapshot FLAT + cycle-free (task 3.5, Requirements 2.1, 3.1):</b> after a create the
 *       {@code audit_log} row's {@code snapshot_after} JSON carries the nested references
 *       ({@code workItem}/{@code offerPackage}/{@code materialUnit}/{@code materialType}) as plain
 *       readable NAME strings, not nested JSON objects.</li>
 * </ul>
 *
 * <p><b>Harness.</b> Mirrors {@link WorkMaterialConsumptionDrillInIntegrationTest}:
 * {@code @SpringBootTest} + MockMvc against a Testcontainers PostgreSQL, {@code create-drop} DDL (so
 * Liquibase is off and this test owns its schema), {@code @WithMockUser(roles="ADMIN")} +
 * {@code @Transactional} rollback so each test seeds its own isolated fixtures without cross-test
 * contamination.
 *
 * <p>Validates: Requirements 2.1 (flat entity + justification pair audited), 3.1 (generic CRUD),
 * 3.8 (justification PL fallback + raw variants), 10.1 (CRUD + validation + boundaries).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class WorkMaterialConsumptionControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private static final String BASE = "/api/work-material-consumptions";
    private static final String ENTITY_CLASS = "WorkMaterialConsumptionEntity";

    /** Per-run unique suffix so seeded reference codes never collide across tests / runs. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkMaterialConsumptionDao consumptionDao;

    @Autowired
    private WorkItemDao workItemDao;

    @Autowired
    private WorkCategoryDao workCategoryDao;

    @Autowired
    private OfferPackageDao offerPackageDao;

    @Autowired
    private MeasurementUnitDao measurementUnitDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Autowired
    private ConstructionMaterialTypeDao constructionMaterialTypeDao;

    @Autowired
    private MaterialTypeDao materialTypeDao;

    @Autowired
    private AuditLogDao auditLogDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String unique(String prefix) {
        return prefix + System.nanoTime() + COUNTER.incrementAndGet();
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private WorkCategoryEntity createCategory() {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode(unique("WC"));
        category.setOrderNo(1);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        return workCategoryDao.save(category);
    }

    private MeasurementUnitEntity createUnit(String nameRU, String namePL) {
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode(unique("MU"));
        unit.setNameRU(nameRU);
        unit.setNamePL(namePL);
        unit.setActive(true);
        return measurementUnitDao.save(unit);
    }

    private CurrencyEntity createCurrency() {
        CurrencyEntity currency = new CurrencyEntity();
        currency.setCode("C" + (COUNTER.incrementAndGet() % 1000));
        currency.setSymbol("zł");
        currency.setNameRU("Злотый");
        currency.setNamePL("Złoty");
        currency.setActive(true);
        return currencyDao.save(currency);
    }

    private OfferPackageEntity createPackage(int orderNo, String nameRU, String namePL) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode(unique("OP"));
        pkg.setOrderNo(orderNo);
        pkg.setNameRU(nameRU);
        pkg.setNamePL(namePL);
        pkg.setActive(true);
        return offerPackageDao.save(pkg);
    }

    private WorkItemEntity createWorkItem(String code, WorkCategoryEntity category,
                                          MeasurementUnitEntity unit, String nameRU, String namePL) {
        WorkItemEntity item = new WorkItemEntity();
        item.setCode(code);
        item.setWorkCategory(category);
        item.setUnit(unit);
        item.setNameRU(nameRU);
        item.setNamePL(namePL);
        item.setActive(true);
        return workItemDao.save(item);
    }

    private ConstructionMaterialTypeEntity createConstructionType(String nameRU, String namePL) {
        ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
        type.setCode(unique("CMT"));
        type.setNameRU(nameRU);
        type.setNamePL(namePL);
        type.setActive(true);
        return constructionMaterialTypeDao.save(type);
    }

    private MaterialTypeEntity createFinishingType(String nameRU, String namePL) {
        MaterialTypeEntity type = new MaterialTypeEntity();
        type.setCode(unique("FMT"));
        type.setNameRU(nameRU);
        type.setNamePL(namePL);
        type.setActive(true);
        return materialTypeDao.save(type);
    }

    /**
     * Holder for a fresh set of construction-branch references used to build a valid create/update
     * body (a work item, an offer package, a numerator material unit, and a construction material
     * type).
     */
    private record ConstructionRefs(WorkItemEntity work, OfferPackageEntity pkg,
                                    MeasurementUnitEntity materialUnit,
                                    ConstructionMaterialTypeEntity type) {}

    private ConstructionRefs seedConstructionRefs() {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity workUnit = createUnit("м2", "m2");
        MeasurementUnitEntity kg = createUnit("кг", "kg");
        OfferPackageEntity budget = createPackage(1, "Бюджет", "Budżet");
        WorkItemEntity work = createWorkItem(unique("1."), category, workUnit, "Работа", "Praca");
        ConstructionMaterialTypeEntity glue = createConstructionType("Клей", "Klej");
        entityManager.flush();
        return new ConstructionRefs(work, budget, kg, glue);
    }

    /**
     * A minimal VALID construction-branch create body (all required references present, exactly one
     * construction type matching {@code branch}, in-range {@code normQty}, bilingual justification,
     * a full citation).
     */
    private String validCreateBody(ConstructionRefs refs, BigDecimal normQty) {
        return String.format("""
                {
                  "workItemId": %d,
                  "offerPackageId": %d,
                  "branch": "construction",
                  "materialUnitId": %d,
                  "constructionMaterialTypeId": %d,
                  "normQty": %s,
                  "justificationRU": "Обоснование нормы расхода",
                  "justificationPL": "Uzasadnienie normy zużycia",
                  "sourceType": "excel",
                  "sourceDoc": "Matrix Foremen v3.0",
                  "sourceRef": "row-%d"
                }""",
                refs.work().getId(), refs.pkg().getId(), refs.materialUnit().getId(),
                refs.type().getId(), normQty.toPlainString(), COUNTER.incrementAndGet());
    }

    private long createAndReturnId(String body) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    // ---------------------------------------------------------------------------------------------
    // CRUD happy path (Requirement 3.1, 10.1)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CREATE with all required refs → 200, echoes fields; READ back; UPDATE; DELETE then READ → 404")
    void crudLifecycle() throws Exception {
        ConstructionRefs refs = seedConstructionRefs();

        // CREATE → 200 echoing the persisted references, branch, normQty and citation.
        MvcResult created = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody(refs, new BigDecimal("5.0000"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.branch").value("construction"))
                .andExpect(jsonPath("$.workItem.id").value(refs.work().getId().intValue()))
                .andExpect(jsonPath("$.offerPackage.id").value(refs.pkg().getId().intValue()))
                .andExpect(jsonPath("$.materialUnit.id").value(refs.materialUnit().getId().intValue()))
                .andExpect(jsonPath("$.materialType.id").value(refs.type().getId().intValue()))
                .andExpect(jsonPath("$.normQty").value(5.0))
                .andExpect(jsonPath("$.sourceType").value("excel"))
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // READ back via GET /{id}.
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) id))
                .andExpect(jsonPath("$.normQty").value(5.0))
                .andExpect(jsonPath("$.workItem.id").value(refs.work().getId().intValue()));

        // UPDATE: mutate normQty to 9.5 (same references, still a valid construction row).
        String updateBody = String.format("""
                {
                  "workItemId": %d,
                  "offerPackageId": %d,
                  "branch": "construction",
                  "materialUnitId": %d,
                  "constructionMaterialTypeId": %d,
                  "normQty": 9.5000,
                  "sourceType": "expert",
                  "sourceDoc": "Matrix Foremen v3.0",
                  "sourceRef": "row-upd-%d"
                }""",
                refs.work().getId(), refs.pkg().getId(), refs.materialUnit().getId(),
                refs.type().getId(), COUNTER.incrementAndGet());
        mockMvc.perform(put(BASE + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.normQty").value(9.5))
                .andExpect(jsonPath("$.sourceType").value("expert"));

        // The mutation is visible on a re-read.
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.normQty").value(9.5));

        // DELETE → 2xx.
        mockMvc.perform(delete(BASE + "/" + id))
                .andExpect(status().is2xxSuccessful());

        // READ after delete → 404.
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------------------------
    // Write validation: rejections persist nothing (Requirements 3.2–3.6, 10.1)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CREATE missing required workItemId → 4xx, nothing persisted")
    void create_missingWorkItemId_rejected() throws Exception {
        ConstructionRefs refs = seedConstructionRefs();
        long before = consumptionDao.count();

        String body = String.format("""
                {
                  "offerPackageId": %d,
                  "branch": "construction",
                  "materialUnitId": %d,
                  "constructionMaterialTypeId": %d,
                  "normQty": 5.0000,
                  "sourceType": "excel",
                  "sourceDoc": "doc",
                  "sourceRef": "ref"
                }""",
                refs.pkg().getId(), refs.materialUnit().getId(), refs.type().getId());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());

        entityManager.flush();
        assertThat(consumptionDao.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("CREATE missing branch → 4xx, nothing persisted")
    void create_missingBranch_rejected() throws Exception {
        ConstructionRefs refs = seedConstructionRefs();
        long before = consumptionDao.count();

        String body = String.format("""
                {
                  "workItemId": %d,
                  "offerPackageId": %d,
                  "materialUnitId": %d,
                  "constructionMaterialTypeId": %d,
                  "normQty": 5.0000,
                  "sourceType": "excel",
                  "sourceDoc": "doc",
                  "sourceRef": "ref"
                }""",
                refs.work().getId(), refs.pkg().getId(), refs.materialUnit().getId(), refs.type().getId());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());

        entityManager.flush();
        assertThat(consumptionDao.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("CREATE missing normQty → 4xx, nothing persisted")
    void create_missingNormQty_rejected() throws Exception {
        ConstructionRefs refs = seedConstructionRefs();
        long before = consumptionDao.count();

        String body = String.format("""
                {
                  "workItemId": %d,
                  "offerPackageId": %d,
                  "branch": "construction",
                  "materialUnitId": %d,
                  "constructionMaterialTypeId": %d,
                  "sourceType": "excel",
                  "sourceDoc": "doc",
                  "sourceRef": "ref"
                }""",
                refs.work().getId(), refs.pkg().getId(), refs.materialUnit().getId(), refs.type().getId());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());

        entityManager.flush();
        assertThat(consumptionDao.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("CREATE with NEITHER material-type id → 4xx, nothing persisted")
    void create_neitherTypeId_rejected() throws Exception {
        ConstructionRefs refs = seedConstructionRefs();
        long before = consumptionDao.count();

        String body = String.format("""
                {
                  "workItemId": %d,
                  "offerPackageId": %d,
                  "branch": "construction",
                  "materialUnitId": %d,
                  "normQty": 5.0000,
                  "sourceType": "excel",
                  "sourceDoc": "doc",
                  "sourceRef": "ref"
                }""",
                refs.work().getId(), refs.pkg().getId(), refs.materialUnit().getId());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());

        entityManager.flush();
        assertThat(consumptionDao.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("CREATE with BOTH material-type ids → 4xx, nothing persisted")
    void create_bothTypeIds_rejected() throws Exception {
        ConstructionRefs refs = seedConstructionRefs();
        MaterialTypeEntity finishing = createFinishingType("Краска", "Farba");
        entityManager.flush();
        long before = consumptionDao.count();

        String body = String.format("""
                {
                  "workItemId": %d,
                  "offerPackageId": %d,
                  "branch": "construction",
                  "materialUnitId": %d,
                  "constructionMaterialTypeId": %d,
                  "finishingMaterialTypeId": %d,
                  "normQty": 5.0000,
                  "sourceType": "excel",
                  "sourceDoc": "doc",
                  "sourceRef": "ref"
                }""",
                refs.work().getId(), refs.pkg().getId(), refs.materialUnit().getId(),
                refs.type().getId(), finishing.getId());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());

        entityManager.flush();
        assertThat(consumptionDao.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("CREATE branch=construction but only finishingMaterialTypeId set → 4xx, nothing persisted")
    void create_typeNotMatchingBranch_rejected() throws Exception {
        ConstructionRefs refs = seedConstructionRefs();
        MaterialTypeEntity finishing = createFinishingType("Краска", "Farba");
        entityManager.flush();
        long before = consumptionDao.count();

        String body = String.format("""
                {
                  "workItemId": %d,
                  "offerPackageId": %d,
                  "branch": "construction",
                  "materialUnitId": %d,
                  "finishingMaterialTypeId": %d,
                  "normQty": 5.0000,
                  "sourceType": "excel",
                  "sourceDoc": "doc",
                  "sourceRef": "ref"
                }""",
                refs.work().getId(), refs.pkg().getId(), refs.materialUnit().getId(), finishing.getId());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());

        entityManager.flush();
        assertThat(consumptionDao.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("CREATE with negative normQty → 4xx, nothing persisted")
    void create_negativeNormQty_rejected() throws Exception {
        ConstructionRefs refs = seedConstructionRefs();
        long before = consumptionDao.count();

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody(refs, new BigDecimal("-1.0000"))))
                .andExpect(status().is4xxClientError());

        entityManager.flush();
        assertThat(consumptionDao.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("CREATE with normQty over 99999999.9999 → 4xx, nothing persisted")
    void create_overMaxNormQty_rejected() throws Exception {
        ConstructionRefs refs = seedConstructionRefs();
        long before = consumptionDao.count();

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody(refs, new BigDecimal("100000000.0000"))))
                .andExpect(status().is4xxClientError());

        entityManager.flush();
        assertThat(consumptionDao.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("CREATE with a dangling workItemId → 4xx, nothing persisted")
    void create_danglingReference_rejected() throws Exception {
        ConstructionRefs refs = seedConstructionRefs();
        long before = consumptionDao.count();

        String body = String.format("""
                {
                  "workItemId": %d,
                  "offerPackageId": %d,
                  "branch": "construction",
                  "materialUnitId": %d,
                  "constructionMaterialTypeId": %d,
                  "normQty": 5.0000,
                  "sourceType": "excel",
                  "sourceDoc": "doc",
                  "sourceRef": "ref"
                }""",
                999_999_999L, refs.pkg().getId(), refs.materialUnit().getId(), refs.type().getId());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());

        entityManager.flush();
        assertThat(consumptionDao.count()).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------
    // justification PL fallback (read DTO) + both raw variants (extended DTO) — Requirement 3.8
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Read DTO exposes single justification (PL fallback); extended DTO carries both raw variants")
    void justification_plFallbackOnRead_bothRawOnExtended() throws Exception {
        ConstructionRefs refs = seedConstructionRefs();
        long id = createAndReturnId(validCreateBody(refs, new BigDecimal("5.0000")));

        // List DTO: single localized `justification` (PL fallback for the default/PL locale).
        String r = "$.content[?(@.id == " + id + ")]";
        mockMvc.perform(get(BASE)
                        .param("query", "workItem.id==" + refs.work().getId())
                        .param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(r + ".justification")
                        .value(org.hamcrest.Matchers.contains("Uzasadnienie normy zużycia")))
                // The list DTO does NOT expose the raw variants.
                .andExpect(jsonPath(r + ".justificationRU").doesNotExist())
                .andExpect(jsonPath(r + ".justificationPL").doesNotExist());

        // Extended DTO (GET /{id}): single justification PLUS both raw variants for editing.
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.justification").value("Uzasadnienie normy zużycia"))
                .andExpect(jsonPath("$.justificationRU").value("Обоснование нормы расхода"))
                .andExpect(jsonPath("$.justificationPL").value("Uzasadnienie normy zużycia"));
    }

    // ---------------------------------------------------------------------------------------------
    // Audit snapshot is FLAT + cycle-free (task 3.5, Requirements 2.1, 3.1)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Audit snapshot_after is FLAT: nested references are plain name strings, not nested JSON")
    void auditSnapshot_isFlatAndCycleFree() throws Exception {
        ConstructionRefs refs = seedConstructionRefs();
        long id = createAndReturnId(validCreateBody(refs, new BigDecimal("5.0000")));
        entityManager.flush();

        List<AuditLogEntity> entries =
                auditLogDao.findByEntityClassAndEntityIdOrderByPerformedAtAsc(ENTITY_CLASS, id);
        assertThat(entries)
                .as("a CREATE must write exactly one audit_log row for the entity")
                .isNotEmpty();

        AuditLogEntity createEntry = entries.get(0);
        assertThat(createEntry.getOperation()).isEqualTo("CREATE");
        assertThat(createEntry.getSnapshotAfter()).as("CREATE snapshot_after must be populated").isNotNull();

        JsonNode snap = objectMapper.readTree(createEntry.getSnapshotAfter());

        // Nested references appear as readable NAME strings (PL name), not nested JSON objects.
        assertThat(snap.get("workItem").isTextual())
                .as("workItem must be a plain name string, not a nested object").isTrue();
        assertThat(snap.get("workItem").asText()).isEqualTo("Praca");

        assertThat(snap.get("offerPackage").isTextual())
                .as("offerPackage must be a plain name string, not a nested object").isTrue();
        assertThat(snap.get("offerPackage").asText()).isEqualTo("Budżet");

        assertThat(snap.get("materialUnit").isTextual())
                .as("materialUnit must be a plain name string, not a nested object").isTrue();
        assertThat(snap.get("materialUnit").asText()).isEqualTo("kg");

        assertThat(snap.get("materialType").isTextual())
                .as("materialType must be a plain name string, not a nested object").isTrue();
        assertThat(snap.get("materialType").asText()).isEqualTo("Klej");

        // Scalars carried verbatim; the justification pair is audited.
        assertThat(snap.get("branch").asText()).isEqualTo("construction");
        assertThat(snap.get("justificationRU").asText()).isEqualTo("Обоснование нормы расхода");
        assertThat(snap.get("justificationPL").asText()).isEqualTo("Uzasadnienie normy zużycia");

        // Cycle-free / flat: no field is itself a JSON object (every value is a scalar).
        snap.fields().forEachRemaining(e -> assertThat(e.getValue().isObject())
                .as("audit snapshot field '%s' must not be a nested JSON object", e.getKey())
                .isFalse());
    }
}
