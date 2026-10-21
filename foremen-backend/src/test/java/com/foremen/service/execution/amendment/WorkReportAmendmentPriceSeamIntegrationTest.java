package com.foremen.service.execution.amendment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.controller.model.WorkReportRoomModel;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.EstimateAmendmentDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.SignableDocumentDao;
import com.foremen.dao.SignableDocumentTypeDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.AmendmentStatus;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ChangeType;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.EstimateAmendmentEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;
import com.foremen.dao.model.SignatureMethod;
import com.foremen.dao.model.SignatureStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportInputMode;
import com.foremen.service.WorkReportService;
import com.foremen.service.model.WorkReportServiceExtendedModel;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-06-09 task 8.1 (Requirements 4.1, 4.2, 4.3, 4.4; design §"Apply the deltas (Requirement 4)") —
 * the <b>price-seam integration test</b> that proves the dormant FOR-06-08
 * {@code WorkReportService.resolveClientUnitPrice} seam goes live once, and only once, an amendment
 * is driven to a genuinely {@link AmendmentStatus#EFFECTIVE} state through the FOR-06-09 signing
 * flow.
 *
 * <p>It mirrors the FOR-06-08 {@code WorkReportAmendmentSeamTest} (which stubs the DAO sum to zero /
 * a positive delta because no amendment could reach {@code EFFECTIVE} in that build), but here the
 * amendment reaches {@code EFFECTIVE} for real: a {@code SENT_FOR_SIGNING} amendment is linked to an
 * all-{@code SIGNED} {@code AMENDMENT} document and reflected onto {@code EFFECTIVE} by the <b>real</b>
 * {@link AmendmentSigningService#onDocumentFullySigned(Long)} (not a hand-forced status), setting the
 * amendment's {@code effectiveDate} to the all-parties-signed date. The whole thing runs against a
 * real PostgreSQL (Testcontainers) so the seam's native sum query
 * ({@code AmendmentLineDao.sumExtraUnitPriceForEffectiveWorkItem}) is exercised against committed
 * rows — this spec changes neither the seam formula nor the FOR-06-06 margin matrix.
 *
 * <p>Both cases are asserted through the public {@link WorkReportService#create(WorkReportServiceExtendedModel)}
 * path on the committed {@link WorkReportEntity} (exactly how production reaches the private seam):
 * <ul>
 *   <li><b>on/after the effective date</b> — a report dated {@code >= effectiveDate} resolves
 *       {@code clientUnitPrice = line.unitPrice + Σ extraUnitPrice} (R4.1, R4.2);</li>
 *   <li><b>before the effective date</b> — a report dated {@code < effectiveDate} resolves the
 *       baseline {@code line.unitPrice} (the seam's {@code effectiveDate <= reportDate} gate, R4.2).</li>
 * </ul>
 *
 * <p><b>Property 8: Price resolution respects the effective-date boundary.</b> For a report applicable
 * date {@code r}, the resolved client unit price is
 * {@code line.unitPrice + (r >= effectiveDate ? Σ extraUnitPrice : 0)} — asserted on both sides of the
 * boundary against committed work reports.
 *
 * <p>Harness mirrors {@code MarginMatrixServiceIntegrationTest}:
 * {@code @SpringBootTest(MOCK)} + {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}
 * where Liquibase is disabled and Hibernate {@code create-drop} builds the schema from the mapped
 * entities (so the seeded {@code AMENDMENT} document type / roles do not pre-populate; this test seeds
 * its own). Every fixture row carries a unique per-run id and {@link #cleanUp()} removes every row this
 * test created (deepest-FK-first) after each test, so the suite re-runs without manual DB cleanup.
 *
 * <p>Validates: Requirements 4.1, 4.2, 4.3, 4.4 — Feature: FOR-06-09-amendment-signing, Property 8
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
@Tag("Feature: FOR-06-09-amendment-signing, Property 8: price resolution respects the effective-date boundary")
class WorkReportAmendmentPriceSeamIntegrationTest {

    private static final AtomicLong COUNTER = new AtomicLong();

    // Frozen baseline estimate-line unit price and the amendment extra-unit-price delta.
    private static final BigDecimal UNIT_PRICE = new BigDecimal("12.50");
    private static final BigDecimal EXTRA_UNIT_PRICE = new BigDecimal("3.50");
    // The amendment becomes EFFECTIVE on the all-parties-signed date; this is the latest signedAt.
    private static final LocalDate EFFECTIVE_DATE = LocalDate.of(2026, 1, 10);

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

    @Autowired
    private WorkReportService workReportService;
    @Autowired
    private AmendmentSigningService amendmentSigningService;

    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private CurrencyDao currencyDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private WorkItemDao workItemDao;
    @Autowired
    private EstimateDao estimateDao;
    @Autowired
    private EstimateLineDao estimateLineDao;
    @Autowired
    private RoleDao roleDao;
    @Autowired
    private UserDao userDao;
    @Autowired
    private ProjectMemberDao projectMemberDao;
    @Autowired
    private RoomDao roomDao;
    @Autowired
    private RoomTypeDao roomTypeDao;
    @Autowired
    private EstimateAmendmentDao amendmentDao;
    @Autowired
    private SignableDocumentDao documentDao;
    @Autowired
    private SignableDocumentTypeDao documentTypeDao;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique run id so fixture codes/names/emails never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tx().executeWithoutResult(status -> {
            // Deepest-FK-first; everything scoped to this run's project / codes / emails.
            exec("delete from WorkReportRoomEntity r where r.report.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from WorkReportEntity r where r.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from AmendmentLineEntity l where l.amendment.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from EstimateAmendmentEntity a where a.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            // Break the amendment -> document FK before removing documents (defensive; the amendment
            // rows are already gone above).
            exec("delete from DocumentSignatureEntity s where s.document.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from SignableDocumentEntity d where d.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from EstimateLineEntity el where el.estimate.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from EstimateEntity e where e.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from ProjectMemberEntity m where m.projectId in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from RoomEntity rm where rm.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from ProjectEntity pr where pr.name like :p");
            exec("delete from UserEntity u where u.email like :p");
            exec("delete from SignableDocumentTypeEntity t where t.code like :p");
            exec("delete from RoomTypeEntity rt where rt.code like :p");
            // Delete the work item by its OWN run-unique code, not by the joined workCategory.code:
            // a bulk delete filtered on the wi.workCategory.code join-path makes Hibernate emit an
            // invalid correlated purge of the work_room_types collection table (SQLGrammarException,
            // "missing FROM-clause entry for table wc1_0"). The work item's code embeds the runId, so
            // this predicate is equivalent and generates valid SQL.
            exec("delete from WorkItemEntity wi where wi.code like :p");
            exec("delete from WorkCategoryEntity wc where wc.code like :p");
            exec("delete from RoleEntity r where r.code like :p");
            // The shared "PLN" currency + "m2" unit fixtures are intentionally NOT deleted (reused).
        });
    }

    private void exec(String jpql) {
        entityManager.createQuery(jpql).setParameter("p", "%" + runId + "%").executeUpdate();
    }

    // =====================================================================================
    // Test
    // =====================================================================================

    @Test
    @DisplayName("once an amendment is driven EFFECTIVE through the signing flow, the price seam adds "
            + "its extraUnitPrice to a report on/after the effective date and leaves a report before it "
            + "at the baseline price (R4.1, R4.2, R4.4; Property 8)")
    void effectiveAmendmentAddsExtraUnitPriceOnlyOnOrAfterEffectiveDate() {
        Fixture f = seedFixture();

        // --- Drive the amendment to a GENUINELY EFFECTIVE state through the signing flow -----------
        // The amendment is SENT_FOR_SIGNING, linked to an all-SIGNED AMENDMENT document whose latest
        // signature completed on EFFECTIVE_DATE. The REAL reflection code reads that date, flips the
        // amendment SENT_FOR_SIGNING -> SIGNED -> EFFECTIVE, and sets its effectiveDate (not a
        // hand-forced status). After this, the FOR-06-08 seam sum goes live for this work item.
        tx().executeWithoutResult(status ->
                amendmentSigningService.onDocumentFullySigned(f.documentId));

        EstimateAmendmentEntity reflected = amendmentDao.findById(f.amendmentId).orElseThrow();
        assertThat(reflected.getStatus())
                .as("the amendment reached EFFECTIVE through the real signing reflection")
                .isEqualTo(AmendmentStatus.EFFECTIVE);
        assertThat(reflected.getEffectiveDate())
                .as("effectiveDate is the all-parties-signed date (max signedAt), R3.1")
                .isEqualTo(EFFECTIVE_DATE);

        // --- Case 1 — a report dated ON the effective date: baseline + Σ extraUnitPrice (R4.1/R4.2) -
        WorkReportEntity onDate = createReport(f, EFFECTIVE_DATE);
        assertThat(onDate.getClientUnitPrice())
                .as("r == effectiveDate ⇒ unitPrice + extraUnitPrice (seam effectiveDate <= reportDate)")
                .isEqualByComparingTo(UNIT_PRICE.add(EXTRA_UNIT_PRICE)); // 12.50 + 3.50 = 16.00
        // revenueValue = round2(16.00 × 4.000) = 64.00 — the delta flows through to frozen revenue.
        assertThat(onDate.getRevenueValue()).isEqualByComparingTo("64.00");

        // --- Case 2 — a report dated AFTER the effective date: still baseline + Σ extraUnitPrice -----
        WorkReportEntity afterDate = createReport(f, EFFECTIVE_DATE.plusDays(5));
        assertThat(afterDate.getClientUnitPrice())
                .as("r > effectiveDate ⇒ unitPrice + extraUnitPrice (R4.2)")
                .isEqualByComparingTo(UNIT_PRICE.add(EXTRA_UNIT_PRICE));

        // --- Case 3 — a report dated BEFORE the effective date: baseline unitPrice only (R4.2) -------
        WorkReportEntity beforeDate = createReport(f, EFFECTIVE_DATE.minusDays(1));
        assertThat(beforeDate.getClientUnitPrice())
                .as("r < effectiveDate ⇒ the baseline unitPrice, the seam contributes nothing (R4.2)")
                .isEqualByComparingTo(UNIT_PRICE); // 12.50
        // revenueValue = round2(12.50 × 4.000) = 50.00 — no amendment contribution.
        assertThat(beforeDate.getRevenueValue()).isEqualByComparingTo("50.00");

        // --- Property 8 — the effective-date boundary holds exactly on both sides ------------------
        // resolveClientUnitPrice(line, r) == line.unitPrice + (r >= effectiveDate ? Σ extra : 0).
        assertEffectiveDateBoundary(f, EFFECTIVE_DATE.minusDays(10), false);
        assertEffectiveDateBoundary(f, EFFECTIVE_DATE.minusDays(1), false);
        assertEffectiveDateBoundary(f, EFFECTIVE_DATE, true);
        assertEffectiveDateBoundary(f, EFFECTIVE_DATE.plusDays(30), true);
    }

    /**
     * Property 8 helper: create a report with its applicable (performedTo) date {@code r} and assert
     * the frozen client unit price equals {@code unitPrice + (onOrAfter ? extraUnitPrice : 0)}.
     */
    private void assertEffectiveDateBoundary(Fixture f, LocalDate reportDate, boolean onOrAfter) {
        WorkReportEntity report = createReport(f, reportDate);
        BigDecimal expected = onOrAfter ? UNIT_PRICE.add(EXTRA_UNIT_PRICE) : UNIT_PRICE;
        assertThat(report.getClientUnitPrice())
                .as("Property 8 — reportDate %s (onOrAfter=%s)", reportDate, onOrAfter)
                .isEqualByComparingTo(expected);
    }

    /**
     * Create a work report through the public {@link WorkReportService#create} path as the project's
     * WORKER (so the Covering_Work + self-only rules pass and the frozen revenue valuation is derived
     * by the real seam). {@code performedTo} is the applicable report date the seam filters by. Runs
     * in its own transaction and returns the committed entity re-read from the DB.
     */
    private WorkReportEntity createReport(Fixture f, LocalDate reportDate) {
        return tx().execute(status -> {
            authenticateWorker(f.workerId);
            try {
                WorkReportServiceExtendedModel model = new WorkReportServiceExtendedModel();
                model.setProjectId(f.projectId);
                model.setWorkItemId(f.workItemId);
                model.setWorkerId(f.workerId);
                model.setPerformedFrom(reportDate);
                model.setPerformedTo(reportDate);
                model.setRooms(new ArrayList<>(List.of(new WorkReportRoomModel(
                        null, f.roomId, null, new BigDecimal("4.000"), null, WorkReportInputMode.ABSOLUTE))));
                WorkReportServiceExtendedModel saved = workReportService.create(model);
                return workReportDaoReReadFlush(saved.getId());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    /** Re-read the committed report (within the create transaction) so its frozen scalars are read back. */
    private WorkReportEntity workReportDaoReReadFlush(Long id) {
        entityManager.flush();
        return entityManager.find(WorkReportEntity.class, id);
    }

    // =====================================================================================
    // Fixture building
    // =====================================================================================

    /** The ids a test needs, captured after the fixture graph is committed. */
    private static final class Fixture {
        Long projectId;
        Long workItemId;
        Long workerId;
        Long roomId;
        Long amendmentId;
        Long documentId;
    }

    /**
     * Seeds a self-contained project graph in one transaction:
     * <ul>
     *   <li>a PLN currency + m2 unit (shared, found-or-created);</li>
     *   <li>a WORKER role + a WORKER user who is an ACTIVE project member covering the work item's
     *       category (so the Covering_Work + self-only create rules pass);</li>
     *   <li>a work category + work item, an estimate with one line priced at {@link #UNIT_PRICE};</li>
     *   <li>one room (ABSOLUTE-mode reporting, no plan needed);</li>
     *   <li>an {@code AMENDMENT} document type + an all-{@code SIGNED} {@code AMENDMENT} document whose
     *       latest signature completed on {@link #EFFECTIVE_DATE};</li>
     *   <li>a {@code SENT_FOR_SIGNING} amendment linked to that document, with one
     *       {@code ADDITIONAL_WORK} line on the work item carrying {@link #EXTRA_UNIT_PRICE}.</li>
     * </ul>
     */
    private Fixture seedFixture() {
        Fixture f = new Fixture();
        tx().executeWithoutResult(status -> {
            CurrencyEntity currency = persistCurrency();
            MeasurementUnitEntity m2 = persistUnit("m2");
            RoleEntity workerRole = persistRole("WORKER_" + runId);

            WorkCategoryEntity category = persistCategory("CAT_" + runId, 1);
            WorkItemEntity workItem = persistWorkItem("WI_" + runId, category, m2);

            ProjectEntity project = persistProject("Price Seam IT");
            project.setStartDate(LocalDate.of(2025, 1, 1));
            project.setEndDate(LocalDate.of(2027, 1, 1));
            projectDao.save(project);

            EstimateEntity estimate = persistEstimate(project, currency);
            persistLine(estimate, workItem, m2, UNIT_PRICE);

            RoomTypeEntity roomType = persistRoomType("RT_" + runId);
            RoomEntity room = persistRoom(project, roomType);

            // A WORKER member who covers the work item's category (Covering_Work, R3.2) and authors
            // their own report (self-only, R10.5).
            UserEntity worker = persistUser("worker-" + runId, "Jan", workerRole);
            persistMember(worker, project.getId(), workerRole, category);

            // The all-SIGNED AMENDMENT document the reflection reads: two SIGNED signatures, the latest
            // completing on EFFECTIVE_DATE (so the all-parties-signed date resolves to EFFECTIVE_DATE).
            SignableDocumentTypeEntity amendmentType = persistAmendmentType("AMENDMENT_" + runId);
            SignableDocumentEntity document = persistSignedAmendmentDocument(project, amendmentType, worker);

            // A SENT_FOR_SIGNING amendment with one ADDITIONAL_WORK cost line on the work item.
            EstimateAmendmentEntity amendment = persistSentForSigningAmendment(project, document, workItem);

            f.projectId = project.getId();
            f.workItemId = workItem.getId();
            f.workerId = worker.getId();
            f.roomId = room.getId();
            f.amendmentId = amendment.getId();
            f.documentId = document.getId();
        });
        return f;
    }

    // --- entity fixtures -----------------------------------------------------------------

    private CurrencyEntity persistCurrency() {
        return currencyDao.findByCode("PLN").orElseGet(() -> {
            CurrencyEntity currency = new CurrencyEntity();
            currency.setCode("PLN");
            currency.setSymbol("zł");
            currency.setNameRU("Злотый");
            currency.setNamePL("Złoty");
            currency.setActive(true);
            return currencyDao.save(currency);
        });
    }

    private MeasurementUnitEntity persistUnit(String code) {
        List<MeasurementUnitEntity> existing = entityManager.createQuery(
                        "select u from MeasurementUnitEntity u where u.code = :code", MeasurementUnitEntity.class)
                .setParameter("code", code)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode(code);
        unit.setNameRU("Ед. " + code);
        unit.setNamePL("Jedn. " + code);
        unit.setActive(true);
        return measurementUnitDao.save(unit);
    }

    private RoleEntity persistRole(String code) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setNameRU("Роль " + runId);
        role.setNamePL("Rola " + runId);
        role.setSystem(false);
        return roleDao.save(role);
    }

    private WorkCategoryEntity persistCategory(String code, int orderNo) {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode(code);
        category.setOrderNo(orderNo);
        category.setNameRU("Категория " + code);
        category.setNamePL("Kategoria " + code);
        category.setActive(true);
        return workCategoryDao.save(category);
    }

    private WorkItemEntity persistWorkItem(String code, WorkCategoryEntity category, MeasurementUnitEntity unit) {
        WorkItemEntity item = new WorkItemEntity();
        item.setWorkCategory(category);
        item.setUnit(unit);
        item.setNameRU("Работа " + code);
        item.setNamePL("Praca " + code);
        item.setCode(code);
        item.setActive(true);
        return workItemDao.save(item);
    }

    private ProjectEntity persistProject(String label) {
        ProjectEntity project = new ProjectEntity();
        project.setName(label + " " + runId + "-" + COUNTER.incrementAndGet());
        project.setStatus(ProjectStatus.ACTIVE);
        return projectDao.save(project);
    }

    private EstimateEntity persistEstimate(ProjectEntity project, CurrencyEntity currency) {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setProject(project);
        estimate.setCurrency(currency);
        estimate.setStatus(EstimateStatus.SIGNED);
        return estimateDao.save(estimate);
    }

    private void persistLine(EstimateEntity estimate, WorkItemEntity workItem, MeasurementUnitEntity unit,
                             BigDecimal unitPrice) {
        EstimateLineEntity line = new EstimateLineEntity();
        line.setEstimate(estimate);
        line.setWorkItem(workItem);
        line.setUnit(unit);
        line.setUnitPrice(unitPrice);
        line.setQuantity(new BigDecimal("10"));
        line.setValueNet(unitPrice.multiply(new BigDecimal("10")));
        estimateLineDao.save(line);
    }

    private RoomTypeEntity persistRoomType(String code) {
        RoomTypeEntity type = new RoomTypeEntity();
        type.setCode(code);
        type.setNameRU("Тип " + code);
        type.setNamePL("Typ " + code);
        type.setActive(true);
        return roomTypeDao.save(type);
    }

    private RoomEntity persistRoom(ProjectEntity project, RoomTypeEntity roomType) {
        RoomEntity room = new RoomEntity();
        room.setProject(project);
        room.setRoomType(roomType);
        room.setLabel("Room " + runId);
        return roomDao.save(room);
    }

    private UserEntity persistUser(String key, String name, RoleEntity role) {
        UserEntity user = new UserEntity();
        user.setName(name);
        user.setEmail(key + "@example.com");
        user.setRole(role);
        user.setActive(true);
        user.setLocale(java.util.Locale.forLanguageTag("pl"));
        return userDao.save(user);
    }

    private void persistMember(UserEntity user, Long projectId, RoleEntity role, WorkCategoryEntity covered) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(role);
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);
        member.setWorkCategories(new ArrayList<>(List.of(covered)));
        projectMemberDao.save(member);
    }

    private SignableDocumentTypeEntity persistAmendmentType(String code) {
        SignableDocumentTypeEntity type = new SignableDocumentTypeEntity();
        // The reflection does not read the type code; a run-unique code keeps the create-drop schema's
        // UNIQUE(code) clean across runs. (Production seeds the stable "AMENDMENT" code.)
        type.setCode(code);
        type.setNamePL("Aneks");
        type.setNameRU("Анекс");
        type.setActive(true);
        return documentTypeDao.save(type);
    }

    /**
     * An all-{@code SIGNED} {@code AMENDMENT} {@link SignableDocumentEntity} (every signature
     * {@code SIGNED} with a {@code signedAt}), whose latest {@code signedAt} is on
     * {@link #EFFECTIVE_DATE} — so {@link AllPartiesSignedDateResolver} resolves the all-parties-signed
     * date to {@code EFFECTIVE_DATE}.
     */
    private SignableDocumentEntity persistSignedAmendmentDocument(
            ProjectEntity project, SignableDocumentTypeEntity type, UserEntity createdBy) {
        SignableDocumentEntity document = new SignableDocumentEntity();
        document.setProject(project);
        document.setDocumentType(type);
        document.setStatus(DocumentStatus.SIGNED);
        document.setTitle("Amendment doc " + runId);
        document.setCreatedByUser(createdBy);

        DocumentSignatureEntity first = signedSignature(document, "CLIENT",
                LocalDateTime.of(2026, 1, 8, 9, 0));
        DocumentSignatureEntity last = signedSignature(document, "MANAGER",
                EFFECTIVE_DATE.atTime(17, 30)); // the latest completion ⇒ the all-parties-signed date
        document.getSignatures().add(first);
        document.getSignatures().add(last);
        return documentDao.save(document);
    }

    private DocumentSignatureEntity signedSignature(
            SignableDocumentEntity document, String role, LocalDateTime signedAt) {
        DocumentSignatureEntity signature = new DocumentSignatureEntity();
        signature.setDocument(document);
        signature.setSignerRole(role);
        signature.setMethod(SignatureMethod.PRINT);
        signature.setStatus(SignatureStatus.SIGNED);
        signature.setSignedAt(signedAt);
        return signature;
    }

    private EstimateAmendmentEntity persistSentForSigningAmendment(
            ProjectEntity project, SignableDocumentEntity document, WorkItemEntity workItem) {
        EstimateAmendmentEntity amendment = new EstimateAmendmentEntity();
        amendment.setProject(project);
        amendment.setSeqNo(1);
        amendment.setStatus(AmendmentStatus.SENT_FOR_SIGNING);
        amendment.setSignableDocument(document);

        AmendmentLineEntity line = new AmendmentLineEntity();
        line.setAmendment(amendment);
        line.setChangeType(ChangeType.ADDITIONAL_WORK);
        line.setWorkItem(workItem);
        line.setDeltaQuantity(new BigDecimal("1.0000"));
        line.setExtraUnitPrice(EXTRA_UNIT_PRICE);
        amendment.setLines(new ArrayList<>(List.of(line)));

        return amendmentDao.save(amendment);
    }

    // --- authentication ------------------------------------------------------------------

    /** Authenticate the WORKER caller (numeric principal + ROLE_WORKER) so create resolves the author. */
    private void authenticateWorker(Long userId) {
        LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("pl"));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
