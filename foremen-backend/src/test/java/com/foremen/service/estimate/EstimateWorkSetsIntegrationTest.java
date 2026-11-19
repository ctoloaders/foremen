package com.foremen.service.estimate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.EstimateService;
import com.foremen.service.StandardWorkSetItemService;
import com.foremen.service.WorkRelatedWorkService;
import com.foremen.service.estimate.EstimateAssignmentService.EditKind;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;
import com.foremen.service.estimate.recalc.RoomsSaveService;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.service.model.StandardWorkSetItemServiceExtendedModel;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-04-11 additional tasks 3, 4, 6 and 8 end to end against PostgreSQL: related works (validation,
 * one-level expansion, cycle safety, preview), the standard work set CRUD and its application when a
 * room is created through {@link RoomsSaveService}.
 *
 * <p>Repeatability: every fixture name carries a per-run id; {@link #cleanUp()} removes the rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
class EstimateWorkSetsIntegrationTest {

    private static final AtomicLong COUNTER = new AtomicLong();

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
    private WorkRelatedWorkService relatedWorkService;
    @Autowired
    private StandardWorkSetItemService standardSetService;
    @Autowired
    private RoomsSaveService roomsSaveService;
    @Autowired
    private CurrencyDao currencyDao;
    @Autowired
    private EstimateLineDao estimateLineDao;
    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private RoomDao roomDao;
    @Autowired
    private RoomTypeDao roomTypeDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private WorkItemDao workItemDao;
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
        tx().executeWithoutResult(status -> {
            String p = "%" + runId + "%";
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
            entityManager.createQuery("delete from StandardWorkSetItemEntity i where i.roomType.id in "
                    + "(select rt.id from RoomTypeEntity rt where rt.code like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from WorkRelatedWorkEntity l where l.workItem.id in "
                    + "(select w.id from WorkItemEntity w where w.workCategory.code like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from WorkItemEntity wi where wi.id in "
                    + "(select w.id from WorkItemEntity w where w.workCategory.code like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from WorkCategoryEntity wc where wc.code like :p").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from RoomTypeEntity rt where rt.code like :p").setParameter("p", p).executeUpdate();
        });
    }

    // --- fixtures -------------------------------------------------------------------------

    private static final class Fixture {
        Long projectId;
        Long roomId;
        Long roomTypeId;
        Long workA;
        Long workB;
        Long workC;
    }

    private Fixture createFixture(boolean createEstimate) {
        Fixture f = new Fixture();
        tx().executeWithoutResult(status -> {
            MeasurementUnitEntity unit = entityManager.createQuery(
                            "select u from MeasurementUnitEntity u where u.code = 'm2'", MeasurementUnitEntity.class)
                    .getResultList().stream().findFirst().orElseGet(() -> {
                        MeasurementUnitEntity u = new MeasurementUnitEntity();
                        u.setCode("m2");
                        u.setNameRU("м2");
                        u.setNamePL("m2");
                        u.setActive(true);
                        return measurementUnitDao.save(u);
                    });
            if (currencyDao.findByCode("PLN").isEmpty()) {
                CurrencyEntity pln = new CurrencyEntity();
                pln.setCode("PLN");
                pln.setSymbol("zł");
                pln.setNameRU("Злотый");
                pln.setNamePL("Złoty");
                pln.setActive(true);
                currencyDao.save(pln);
            }
            WorkCategoryEntity category = new WorkCategoryEntity();
            category.setCode("CAT_" + runId);
            category.setOrderNo(1);
            category.setNameRU("Категория " + runId);
            category.setNamePL("Kategoria " + runId);
            category.setActive(true);
            workCategoryDao.save(category);
            f.workA = persistWork("A", category, unit);
            f.workB = persistWork("B", category, unit);
            f.workC = persistWork("C", category, unit);

            ProjectEntity project = new ProjectEntity();
            project.setName("WorkSets IT " + runId + "-" + COUNTER.incrementAndGet());
            project.setStatus(ProjectStatus.DRAFT);
            projectDao.save(project);
            RoomTypeEntity roomType = new RoomTypeEntity();
            roomType.setCode("RT_" + runId);
            roomType.setNameRU("Кухня " + runId);
            roomType.setNamePL("Kuchnia " + runId);
            roomType.setActive(true);
            roomTypeDao.save(roomType);
            RoomEntity room = new RoomEntity();
            room.setProject(project);
            room.setRoomType(roomType);
            room.setLabel("Room " + runId);
            room.setFloorArea(new BigDecimal("12.00"));
            roomDao.save(room);
            if (createEstimate) {
                estimateService.getOrCreateForProject(project.getId());
            }
            f.projectId = project.getId();
            f.roomId = room.getId();
            f.roomTypeId = roomType.getId();
        });
        return f;
    }

    private Long persistWork(String suffix, WorkCategoryEntity category, MeasurementUnitEntity unit) {
        WorkItemEntity item = new WorkItemEntity();
        item.setWorkCategory(category);
        item.setUnit(unit);
        item.setNameRU("Работа " + suffix + runId);
        item.setNamePL("Praca " + suffix + runId);
        item.setCode(suffix + "_" + runId);
        item.setActive(true);
        return workItemDao.save(item).getId();
    }

    private List<Long> workIdsInRoom(Long projectId, Long roomId) {
        return tx().execute(status -> estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId).stream()
                .filter(l -> l.getRoomQtys().stream().anyMatch(rq -> roomId.equals(rq.getRoom().getId())))
                .map(l -> l.getWorkItem().getId())
                .sorted()
                .toList());
    }

    // --- related works: validation --------------------------------------------------------

    @Test
    @DisplayName("related works: self link, duplicate and unknown work are rejected; replace keeps the set exact")
    void relatedWorksValidationAndReplace() {
        Fixture f = createFixture(true);
        assertThatThrownBy(() -> relatedWorkService.setRelatedIds(f.workA, List.of(f.workA)))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(HttpStatus.BAD_REQUEST.value()));
        assertThatThrownBy(() -> relatedWorkService.setRelatedIds(f.workA, List.of(f.workB, f.workB)))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(HttpStatus.BAD_REQUEST.value()));
        assertThatThrownBy(() -> relatedWorkService.setRelatedIds(f.workA, List.of(-1L)))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(HttpStatus.NOT_FOUND.value()));

        assertThat(relatedWorkService.setRelatedIds(f.workA, List.of(f.workB, f.workC))).containsExactly(f.workB, f.workC);
        assertThat(relatedWorkService.setRelatedIds(f.workA, List.of(f.workC))).containsExactly(f.workC);
        assertThat(relatedWorkService.getRelatedIds(f.workB)).as("links are one-way").isEmpty();
        assertThat(relatedWorkService.setRelatedIds(f.workA, List.of())).isEmpty();
    }

    // --- related works: estimate expansion ------------------------------------------------

    @Test
    @DisplayName("assigning a parent also assigns its related works, one level only (A -> B -> C adds A and B)")
    void expansionIsOneLevel() {
        Fixture f = createFixture(true);
        relatedWorkService.setRelatedIds(f.workA, List.of(f.workB));
        relatedWorkService.setRelatedIds(f.workB, List.of(f.workC));

        assignmentService.saveAndAssemble(f.projectId, List.of(StagedEdit.assign(f.workA, f.roomId, null)));

        assertThat(workIdsInRoom(f.projectId, f.roomId)).containsExactlyInAnyOrder(f.workA, f.workB);
    }

    @Test
    @DisplayName("a cycle A <-> B terminates: both works are assigned once")
    void cycleIsIgnored() {
        Fixture f = createFixture(true);
        relatedWorkService.setRelatedIds(f.workA, List.of(f.workB));
        relatedWorkService.setRelatedIds(f.workB, List.of(f.workA));

        assignmentService.saveAndAssemble(f.projectId, List.of(StagedEdit.assign(f.workA, f.roomId, null)));

        assertThat(workIdsInRoom(f.projectId, f.roomId)).containsExactlyInAnyOrder(f.workA, f.workB);
    }

    @Test
    @DisplayName("assigning the related work alone does not add its parent (links are one-way)")
    void linkIsOneWay() {
        Fixture f = createFixture(true);
        relatedWorkService.setRelatedIds(f.workA, List.of(f.workB));

        assignmentService.saveAndAssemble(f.projectId, List.of(StagedEdit.assign(f.workB, f.roomId, null)));

        assertThat(workIdsInRoom(f.projectId, f.roomId)).containsExactly(f.workB);
    }

    @Test
    @DisplayName("the staged preview shows the related work and persists nothing")
    void previewIncludesRelatedAndPersistsNothing() {
        Fixture f = createFixture(true);
        relatedWorkService.setRelatedIds(f.workA, List.of(f.workB));

        EstimateMatrixDto preview = assignmentService.previewStagedEdits(
                f.projectId, List.of(StagedEdit.assign(f.workA, f.roomId, null)), true);

        List<Long> assigned = preview.groups().stream()
                .flatMap(g -> g.rows().stream())
                .filter(r -> r.cells().stream().anyMatch(c -> c.assigned()))
                .map(r -> r.workItemId())
                .sorted()
                .toList();
        assertThat(assigned).containsExactlyInAnyOrder(f.workA, f.workB);
        assertThat(workIdsInRoom(f.projectId, f.roomId)).isEmpty();
    }

    @Test
    @DisplayName("an already assigned related cell keeps its Volume (not overridden by the expansion)")
    void existingRelatedCellIsNotOverridden() {
        Fixture f = createFixture(true);
        relatedWorkService.setRelatedIds(f.workA, List.of(f.workB));
        assignmentService.saveAndAssemble(f.projectId, List.of(
                StagedEdit.assign(f.workB, f.roomId, null),
                new StagedEdit(EditKind.SET_QUANTITY, f.workB, f.roomId, null, null, null, null, null, null,
                        new BigDecimal("3.000"))));

        assignmentService.saveAndAssemble(f.projectId, List.of(StagedEdit.assign(f.workA, f.roomId, null)));

        BigDecimal volume = tx().execute(status -> estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(f.projectId)
                .stream().filter(l -> l.getWorkItem().getId().equals(f.workB))
                .flatMap(l -> l.getRoomQtys().stream())
                .map(rq -> rq.getQuantity()).findFirst().orElseThrow());
        assertThat(volume).isEqualByComparingTo("3.000");
    }

    // --- standard work set ----------------------------------------------------------------

    @Test
    @DisplayName("standard set: a duplicate (room type, work) pair is rejected with 409")
    void standardSetRejectsDuplicate() {
        Fixture f = createFixture(true);
        standardSetService.create(new StandardWorkSetItemServiceExtendedModel(null, f.roomTypeId, f.workA));

        assertThatThrownBy(() -> standardSetService.create(
                new StandardWorkSetItemServiceExtendedModel(null, f.roomTypeId, f.workA)))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(HttpStatus.CONFLICT.value()));
    }

    @Test
    @DisplayName("creating a room applies the standard set of its type (with related works) to the estimate")
    void roomCreationAppliesStandardSet() {
        Fixture f = createFixture(true);
        standardSetService.create(new StandardWorkSetItemServiceExtendedModel(null, f.roomTypeId, f.workA));
        relatedWorkService.setRelatedIds(f.workA, List.of(f.workB));

        Long newRoomId = createRoom(f).created().get(0);

        assertThat(workIdsInRoom(f.projectId, newRoomId)).containsExactlyInAnyOrder(f.workA, f.workB);
    }

    @Test
    @DisplayName("creating a room in a project without an estimate creates the estimate and applies the set")
    void roomCreationWithoutEstimateAppliesStandardSet() {
        Fixture f = createFixture(false);
        standardSetService.create(new StandardWorkSetItemServiceExtendedModel(null, f.roomTypeId, f.workC));

        Long newRoomId = createRoom(f).created().get(0);

        assertThat(workIdsInRoom(f.projectId, newRoomId)).containsExactly(f.workC);
    }

    @Test
    @DisplayName("a room type without a standard set is created without estimate changes")
    void roomCreationWithoutSetChangesNothing() {
        Fixture f = createFixture(true);

        Long newRoomId = createRoom(f).created().get(0);

        assertThat(workIdsInRoom(f.projectId, newRoomId)).isEmpty();
    }

    private RoomsSaveService.Result createRoom(Fixture f) {
        RoomServiceExtendedModel model = new RoomServiceExtendedModel();
        model.setProjectId(f.projectId);
        model.setRoomTypeId(f.roomTypeId);
        model.setLabel("New room " + runId);
        model.setFloorArea(new BigDecimal("10.00"));
        return roomsSaveService.save(f.projectId, List.of(model), List.of(), List.of(), true);
    }
}
