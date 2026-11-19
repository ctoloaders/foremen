package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.EstimateService;
import com.foremen.service.estimate.matrix.CellDto;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-05-05c bug condition exploration test (Property 1: Bug Condition).
 *
 * <p>A labour price adjustment must also reach cells that exist only as staged edits (an unsaved
 * {@code ASSIGN}). On UNFIXED code the only way to adjust is {@code applyLabourPriceAdjustment}, which
 * iterates persisted {@code estimate_lines} only, so the staged cell keeps {@code unitPrice × volume}.
 * This test is EXPECTED TO FAIL on unfixed code; the failure confirms root cause 3. The staged-kind
 * assertions ({@code ADJUST_LABOUR_PRICE}) are completed in task 5.4.
 *
 * <p>Repeatability: every fixture row carries a per-run id and {@link #cleanUp()} deletes it.
 *
 * <p><b>Validates: Requirements 1.2, 2.2</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class LabourPriceAdjustmentExplorationIntegrationTest {

    private static final BigDecimal UNIT_PRICE = new BigDecimal("150.00");
    private static final BigDecimal FLOOR_AREA = new BigDecimal("20.00");

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
    private EstimateAssignmentService assignmentService;
    @Autowired
    private EstimateService estimateService;
    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private RoomDao roomDao;
    @Autowired
    private RoomTypeDao roomTypeDao;
    @Autowired
    private CurrencyDao currencyDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private WorkItemDao workItemDao;
    @Autowired
    private WorkPriceDao workPriceDao;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        String p = "%" + runId + "%";
        tx().executeWithoutResult(status -> {
            entityManager.createQuery("delete from EstimateLineRoomMaterialEntity m where m.roomQty.line.estimate.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from EstimateLineRoomQtyEntity rq where rq.line.estimate.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from EstimateLineEntity l where l.estimate.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from EstimateEntity e where e.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from RoomEntity r where r.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from ProjectEntity pr where pr.name like :p").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from WorkPriceEntity wp where wp.workItem.id in "
                    + "(select wi.id from WorkItemEntity wi where wi.workCategory.code like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from WorkItemEntity wi where wi.id in "
                    + "(select w.id from WorkItemEntity w where w.workCategory.code like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from WorkCategoryEntity wc where wc.code like :p").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from RoomTypeEntity rt where rt.code like :p").setParameter("p", p).executeUpdate();
        });
    }

    static Stream<Arguments> scopesAndPercents() {
        return Stream.of(LabourPriceAdjustmentScope.values())
                .flatMap(scope -> Stream.of("10", "5", "-20").map(pct -> Arguments.of(scope, new BigDecimal(pct))));
    }

    @ParameterizedTest(name = "{0} pct={1}: a staged-only ASSIGN cell gets the adjusted labour")
    @MethodSource("scopesAndPercents")
    void stagedOnlyCellIsAdjusted(LabourPriceAdjustmentScope scope, BigDecimal pct) {
        long[] ids = new long[4]; // projectId, roomId, workItemId, categoryId
        tx().executeWithoutResult(status -> {
            CurrencyEntity currency = currencyDao.findByCode("PLN").orElseGet(() -> {
                CurrencyEntity c = new CurrencyEntity();
                c.setCode("PLN");
                c.setSymbol("zł");
                c.setNameRU("Злотый");
                c.setNamePL("Złoty");
                c.setActive(true);
                return currencyDao.save(c);
            });
            MeasurementUnitEntity m2 = entityManager.createQuery(
                            "select u from MeasurementUnitEntity u where u.code = :c", MeasurementUnitEntity.class)
                    .setParameter("c", "m2").getResultList().stream().findFirst().orElseGet(() -> {
                        MeasurementUnitEntity u = new MeasurementUnitEntity();
                        u.setCode("m2");
                        u.setNameRU("Ед. m2");
                        u.setNamePL("Jedn. m2");
                        u.setActive(true);
                        return measurementUnitDao.save(u);
                    });
            WorkCategoryEntity category = new WorkCategoryEntity();
            category.setCode("CAT_" + runId);
            category.setOrderNo(1);
            category.setNameRU("Категория " + runId);
            category.setNamePL("Kategoria " + runId);
            category.setActive(true);
            category = workCategoryDao.save(category);
            WorkItemEntity work = new WorkItemEntity();
            work.setWorkCategory(category);
            work.setUnit(m2);
            work.setCode("W_" + runId);
            work.setNameRU("Работа " + runId);
            work.setNamePL("Praca " + runId);
            work.setActive(true);
            work = workItemDao.save(work);
            WorkPriceEntity price = new WorkPriceEntity();
            price.setWorkItem(work);
            price.setCurrency(currency);
            price.setNetPrice(UNIT_PRICE);
            workPriceDao.save(price);

            ProjectEntity project = new ProjectEntity();
            project.setName("Labour Adj Exploration " + runId + "-" + scope + "-" + pct);
            project.setStatus(ProjectStatus.DRAFT);
            project = projectDao.save(project);
            RoomTypeEntity roomType = new RoomTypeEntity();
            roomType.setCode("ROOMTYPE_" + runId);
            roomType.setNameRU("Комната " + runId);
            roomType.setNamePL("Pokój " + runId);
            roomType.setActive(true);
            roomType = roomTypeDao.save(roomType);
            RoomEntity room = new RoomEntity();
            room.setProject(project);
            room.setRoomType(roomType);
            room.setLabel("Room " + runId);
            room.setFloorArea(FLOOR_AREA);
            room = roomDao.save(room);
            estimateService.getOrCreateForProject(project.getId());

            ids[0] = project.getId();
            ids[1] = room.getId();
            ids[2] = work.getId();
            ids[3] = category.getId();
        });
        Long projectId = ids[0];
        Long roomId = ids[1];
        Long workId = ids[2];
        Long targetId = switch (scope) {
            case PROJECT -> null;
            case WORK_CATEGORY -> ids[3];
            case WORK_ITEM -> ids[2];
        };

        List<EstimateAssignmentService.StagedEdit> staged =
                List.of(EstimateAssignmentService.StagedEdit.assign(workId, roomId, null));

        // Baseline: the staged ASSIGN previews at the original labour.
        CellDto before = findCell(assignmentService.previewStagedEdits(projectId, staged, true), workId, roomId);
        assertThat(before).as("staged ASSIGN cell must be previewed").isNotNull();
        assertThat(before.labour()).isEqualByComparingTo(UNIT_PRICE.multiply(FLOOR_AREA));

        // Fixed behaviour (FOR-05-05c): the adjustment is itself a staged edit replayed after the ASSIGN,
        // so it reaches the staged-only cell. (The legacy direct write only sees persisted lines.)
        List<EstimateAssignmentService.StagedEdit> withAdjustment = List.of(
                EstimateAssignmentService.StagedEdit.assign(workId, roomId, null),
                EstimateAssignmentService.StagedEdit.adjustLabourPrice(scope, targetId, pct));
        CellDto after = findCell(assignmentService.previewStagedEdits(projectId, withAdjustment, true), workId, roomId);

        BigDecimal adjustedUnit = UNIT_PRICE
                .multiply(BigDecimal.ONE.add(pct.divide(new BigDecimal("100"))))
                .setScale(2, RoundingMode.HALF_UP);
        assertThat(after).isNotNull();
        assertThat(after.labour())
                .as("staged-only cell must carry round2(unitPrice x (1 + %s/100)) x volume for scope %s", pct, scope)
                .isEqualByComparingTo(adjustedUnit.multiply(FLOOR_AREA));
    }

    private CellDto findCell(EstimateMatrixDto matrix, Long workItemId, Long roomId) {
        return matrix.groups().stream()
                .flatMap(group -> group.rows().stream())
                .flatMap(row -> row.cells().stream())
                .filter(cell -> cell != null && workItemId.equals(cell.workItemId()) && roomId.equals(cell.roomId()))
                .findFirst()
                .orElse(null);
    }
}
