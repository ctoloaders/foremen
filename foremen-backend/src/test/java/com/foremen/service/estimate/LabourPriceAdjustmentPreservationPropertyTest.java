package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.EstimateService;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;
import com.foremen.service.estimate.matrix.CellDto;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import net.jqwik.api.Arbitraries;

/**
 * FOR-05-05c preservation tests (Property 2: Preservation), written BEFORE the fix and expected to PASS on
 * unfixed code. They pin the baseline that the fix must not change:
 *
 * <ul>
 *   <li>the saved result of {@code applyLabourPriceAdjustment} for every scope and random {@code pct}
 *       ({@code unit_price_override = round2(unit_price × (1 + pct/100))} HALF_UP, {@code pct = 0} resets,
 *       {@code unit_price} / {@code value_net} / material lines untouched) - requirements 3.1, 3.2, 3.7;</li>
 *   <li>validation, DRAFT gate and unknown-target behaviour - requirement 3.3;</li>
 *   <li>the preview of random staged sets made only of existing kinds (assign, unassign, set quantity)
 *       against an independent model, equal to the batched Save result, and never persisting - 3.5.</li>
 * </ul>
 *
 * <p>The properties are driven by jqwik arbitraries sampled inside JUnit tests: the cases share a
 * Testcontainers-backed Spring context, which the jqwik engine does not inject into.
 *
 * <p>Repeatability: every fixture row carries a per-run id and {@link #cleanUp()} deletes it.
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.5, 3.7</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class LabourPriceAdjustmentPreservationPropertyTest {

    private static final BigDecimal PRICE_A1 = new BigDecimal("133.37");
    private static final BigDecimal PRICE_A2 = new BigDecimal("200.00");
    private static final BigDecimal PRICE_B1 = new BigDecimal("99.99");
    private static final BigDecimal AREA_1 = new BigDecimal("20.00");
    private static final BigDecimal AREA_2 = new BigDecimal("12.50");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int PCT_SAMPLES = 25;
    private static final int SET_SAMPLES = 12;

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
    private MaterialTypeDao materialTypeDao;
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
            entityManager.createQuery("delete from MaterialTypeEntity mt where mt.code like :p").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from RoomTypeEntity rt where rt.code like :p").setParameter("p", p).executeUpdate();
        });
    }

    // ------------------------------------------------------------------------------------------
    // Fixture: category A (works A1, A2), category B (work B1), two rooms.
    // ------------------------------------------------------------------------------------------

    private static final class Fx {
        Long projectId;
        Long room1;
        Long room2;
        Long a1;
        Long a2;
        Long b1;
        Long categoryA;
        Long categoryB;
        Long finishingTypeId;
    }

    private Fx createFixture() {
        Fx f = new Fx();
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
            WorkCategoryEntity catA = persistCategory("CATA_" + runId, 1);
            WorkCategoryEntity catB = persistCategory("CATB_" + runId, 2);
            WorkItemEntity a1 = persistWork("WA1_" + runId, catA, m2, currency, PRICE_A1);
            WorkItemEntity a2 = persistWork("WA2_" + runId, catA, m2, currency, PRICE_A2);
            WorkItemEntity b1 = persistWork("WB1_" + runId, catB, m2, currency, PRICE_B1);

            MaterialTypeEntity type = new MaterialTypeEntity();
            type.setCode("FT_" + runId);
            type.setNameRU("Тип " + runId);
            type.setNamePL("Typ " + runId);
            type.setActive(true);
            type = materialTypeDao.save(type);

            ProjectEntity project = new ProjectEntity();
            project.setName("Labour Adj Preservation " + runId);
            project.setStatus(ProjectStatus.DRAFT);
            project = projectDao.save(project);

            RoomTypeEntity roomType = new RoomTypeEntity();
            roomType.setCode("ROOMTYPE_" + runId);
            roomType.setNameRU("Комната " + runId);
            roomType.setNamePL("Pokój " + runId);
            roomType.setActive(true);
            roomType = roomTypeDao.save(roomType);

            RoomEntity r1 = persistRoom(project, roomType, "R1 " + runId, AREA_1);
            RoomEntity r2 = persistRoom(project, roomType, "R2 " + runId, AREA_2);
            estimateService.getOrCreateForProject(project.getId());

            f.projectId = project.getId();
            f.room1 = r1.getId();
            f.room2 = r2.getId();
            f.a1 = a1.getId();
            f.a2 = a2.getId();
            f.b1 = b1.getId();
            f.categoryA = catA.getId();
            f.categoryB = catB.getId();
            f.finishingTypeId = type.getId();
        });
        return f;
    }

    private WorkCategoryEntity persistCategory(String code, int order) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setCode(code);
        c.setOrderNo(order);
        c.setNameRU("Категория " + code);
        c.setNamePL("Kategoria " + code);
        c.setActive(true);
        return workCategoryDao.save(c);
    }

    private WorkItemEntity persistWork(
            String code, WorkCategoryEntity cat, MeasurementUnitEntity unit, CurrencyEntity cur, BigDecimal price) {
        WorkItemEntity w = new WorkItemEntity();
        w.setWorkCategory(cat);
        w.setUnit(unit);
        w.setCode(code);
        w.setNameRU("Работа " + code);
        w.setNamePL("Praca " + code);
        w.setActive(true);
        w = workItemDao.save(w);
        WorkPriceEntity wp = new WorkPriceEntity();
        wp.setWorkItem(w);
        wp.setCurrency(cur);
        wp.setNetPrice(price);
        workPriceDao.save(wp);
        return w;
    }

    private RoomEntity persistRoom(ProjectEntity project, RoomTypeEntity type, String label, BigDecimal area) {
        RoomEntity room = new RoomEntity();
        room.setProject(project);
        room.setRoomType(type);
        room.setLabel(label);
        room.setFloorArea(area);
        return roomDao.save(room);
    }

    /** Assigns A1, A2, B1 to room 1 directly and adds one finishing material line to A1's cell. */
    private void assignAllAndAddMaterial(Fx f) {
        EstimateLineRoomQtyEntity a1Cell = assignmentService.assign(f.projectId, f.a1, f.room1, null);
        assignmentService.assign(f.projectId, f.a2, f.room1, null);
        assignmentService.assign(f.projectId, f.b1, f.room1, null);
        assignmentService.addMaterialLine(f.projectId, a1Cell.getId(), ConsumptionBranch.finishing, f.finishingTypeId);
    }

    // ------------------------------------------------------------------------------------------
    // Persisted-state observers
    // ------------------------------------------------------------------------------------------

    private record LineState(BigDecimal unitPrice, BigDecimal override, BigDecimal valueNet) { }

    private Map<Long, LineState> lineStates(Long projectId) {
        return tx().execute(status -> {
            Map<Long, LineState> out = new HashMap<>();
            List<Object[]> rows = entityManager.createQuery(
                            "select l.workItem.id, l.unitPrice, l.unitPriceOverride, l.valueNet "
                                    + "from EstimateLineEntity l where l.estimate.project.id = :p", Object[].class)
                    .setParameter("p", projectId).getResultList();
            for (Object[] r : rows) {
                out.put((Long) r[0], new LineState((BigDecimal) r[1], (BigDecimal) r[2], (BigDecimal) r[3]));
            }
            return out;
        });
    }

    private List<String> materialSnapshot(Long projectId) {
        return tx().execute(status -> entityManager.createQuery(
                        "select concat(m.id, '|', m.branch, '|', coalesce(m.normQty, -1), '|', "
                                + "coalesce(m.rangeMin, -1), '|', coalesce(m.rangeMax, -1), '|', "
                                + "coalesce(m.manualQty, -1), '|', coalesce(m.concreteNet, -1)) "
                                + "from EstimateLineRoomMaterialEntity m "
                                + "where m.roomQty.line.estimate.project.id = :p order by m.id", String.class)
                .setParameter("p", projectId).getResultList());
    }

    private static BigDecimal round2Adjusted(BigDecimal unitPrice, BigDecimal pct) {
        return unitPrice.multiply(BigDecimal.ONE.add(pct.divide(HUNDRED))).setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean inScope(Fx f, LabourPriceAdjustmentScope scope, Long targetId, Long workId) {
        return switch (scope) {
            case PROJECT -> true;
            case WORK_ITEM -> workId.equals(targetId);
            case WORK_CATEGORY -> (workId.equals(f.a1) || workId.equals(f.a2))
                    ? f.categoryA.equals(targetId) : f.categoryB.equals(targetId);
        };
    }

    private static Long targetFor(Fx f, LabourPriceAdjustmentScope scope, int variant) {
        return switch (scope) {
            case PROJECT -> null;
            case WORK_CATEGORY -> variant % 2 == 0 ? f.categoryA : f.categoryB;
            case WORK_ITEM -> variant % 3 == 0 ? f.a1 : variant % 3 == 1 ? f.a2 : f.b1;
        };
    }

    /** Random signed percentages with scale 0-2, including negatives and 0. */
    private static List<BigDecimal> samplePcts() {
        List<BigDecimal> out = new ArrayList<>();
        out.add(BigDecimal.ZERO);
        out.add(new BigDecimal("0.00"));
        out.add(new BigDecimal("-100"));
        out.addAll(Arbitraries.integers().between(-9500, 30000).map(i -> BigDecimal.valueOf(i, 2))
                .list().ofSize(PCT_SAMPLES).sample());
        out.addAll(Arbitraries.integers().between(-95, 300).map(BigDecimal::valueOf)
                .list().ofSize(PCT_SAMPLES / 2).sample());
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // 3.1 / 3.2 / 3.7: persisted result of applyLabourPriceAdjustment
    // ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}: override = round2(unit_price x (1 + pct/100)), pct 0 resets, rest untouched")
    @EnumSource(LabourPriceAdjustmentScope.class)
    void persistedOverridesEqualTheFormulaForEveryScope(LabourPriceAdjustmentScope scope) {
        Fx f = createFixture();
        assignAllAndAddMaterial(f);
        Map<Long, LineState> baseline = lineStates(f.projectId);
        List<String> materialsBefore = materialSnapshot(f.projectId);
        assertThat(baseline).hasSize(3);
        assertThat(materialsBefore).as("fixture must carry a material line").isNotEmpty();

        int variant = 0;
        for (BigDecimal pct : samplePcts()) {
            Long targetId = targetFor(f, scope, variant++);
            // Start every iteration from a known state: reset everything to the original price.
            assignmentService.applyLabourPriceAdjustment(f.projectId, LabourPriceAdjustmentScope.PROJECT, null, BigDecimal.ZERO);
            Map<Long, LineState> reset = lineStates(f.projectId);
            reset.forEach((work, st) -> assertThat(st.override()).isEqualByComparingTo(st.unitPrice()));

            assignmentService.applyLabourPriceAdjustment(f.projectId, scope, targetId, pct);

            Map<Long, LineState> after = lineStates(f.projectId);
            for (Map.Entry<Long, LineState> e : after.entrySet()) {
                LineState before = baseline.get(e.getKey());
                LineState now = e.getValue();
                BigDecimal expected = inScope(f, scope, targetId, e.getKey())
                        ? round2Adjusted(before.unitPrice(), pct)
                        : before.unitPrice();
                assertThat(now.override())
                        .as("scope=%s target=%s pct=%s work=%s", scope, targetId, pct, e.getKey())
                        .isEqualByComparingTo(expected);
                assertThat(now.unitPrice()).as("unit_price untouched").isEqualByComparingTo(before.unitPrice());
                assertThat(now.valueNet()).as("value_net untouched").isEqualByComparingTo(before.valueNet());
            }
            assertThat(materialSnapshot(f.projectId)).as("material lines untouched").isEqualTo(materialsBefore);
        }
    }

    @Test
    void pctZeroResetsAnAdjustedOverrideToTheOriginal() {
        Fx f = createFixture();
        assignAllAndAddMaterial(f);
        assignmentService.applyLabourPriceAdjustment(
                f.projectId, LabourPriceAdjustmentScope.PROJECT, null, new BigDecimal("37.5"));
        assertThat(lineStates(f.projectId).get(f.a1).override())
                .isEqualByComparingTo(round2Adjusted(PRICE_A1, new BigDecimal("37.5")));

        assignmentService.applyLabourPriceAdjustment(
                f.projectId, LabourPriceAdjustmentScope.WORK_ITEM, f.a1, BigDecimal.ZERO);

        Map<Long, LineState> after = lineStates(f.projectId);
        assertThat(after.get(f.a1).override()).isEqualByComparingTo(PRICE_A1);
        assertThat(after.get(f.a2).override()).as("other work keeps its adjustment")
                .isEqualByComparingTo(round2Adjusted(PRICE_A2, new BigDecimal("37.5")));
    }

    @Test
    void adjustmentDrivesMatrixLabourButNotValueNet() {
        Fx f = createFixture();
        assignAllAndAddMaterial(f);
        BigDecimal valueNetBefore = lineStates(f.projectId).get(f.a1).valueNet();
        assignmentService.applyLabourPriceAdjustment(
                f.projectId, LabourPriceAdjustmentScope.WORK_ITEM, f.a1, new BigDecimal("10"));

        CellDto cell = findCell(assignmentService.getMatrix(f.projectId, true), f.a1, f.room1);
        assertThat(cell.labour()).isEqualByComparingTo(round2Adjusted(PRICE_A1, new BigDecimal("10")).multiply(AREA_1));
        assertThat(lineStates(f.projectId).get(f.a1).valueNet()).isEqualByComparingTo(valueNetBefore);
    }

    // ------------------------------------------------------------------------------------------
    // 3.3: validation, DRAFT gate, unknown target
    // ------------------------------------------------------------------------------------------

    @Test
    void invalidInputsAreRejectedWithValidationError() {
        Fx f = createFixture();
        assignAllAndAddMaterial(f);
        Map<Long, LineState> baseline = lineStates(f.projectId);

        assertValidation(() -> assignmentService.applyLabourPriceAdjustment(f.projectId, null, f.a1, BigDecimal.TEN));
        assertValidation(() -> assignmentService.applyLabourPriceAdjustment(
                f.projectId, LabourPriceAdjustmentScope.PROJECT, null, null));
        assertValidation(() -> assignmentService.applyLabourPriceAdjustment(
                f.projectId, LabourPriceAdjustmentScope.WORK_CATEGORY, null, BigDecimal.TEN));
        assertValidation(() -> assignmentService.applyLabourPriceAdjustment(
                f.projectId, LabourPriceAdjustmentScope.WORK_ITEM, null, BigDecimal.TEN));

        Map<Long, LineState> after = lineStates(f.projectId);
        assertThat(after).as("rejected calls must not write").isEqualTo(baseline);
    }

    private static void assertValidation(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageCode()).isEqualTo("error.validation");
                });
    }

    @Test
    void nonDraftEstimateIsRejectedAndNothingChanges() {
        Fx f = createFixture();
        assignAllAndAddMaterial(f);
        Map<Long, LineState> baseline = lineStates(f.projectId);
        tx().executeWithoutResult(status -> {
            EstimateEntity estimate = entityManager.createQuery(
                            "select e from EstimateEntity e where e.project.id = :p", EstimateEntity.class)
                    .setParameter("p", f.projectId).getSingleResult();
            estimate.setStatus(EstimateStatus.PRICED);
        });

        assertThatThrownBy(() -> assignmentService.applyLabourPriceAdjustment(
                f.projectId, LabourPriceAdjustmentScope.PROJECT, null, new BigDecimal("10")))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessageCode()).isEqualTo("error.estimate.locked");
                });
        assertThat(lineStates(f.projectId)).isEqualTo(baseline);
    }

    @Test
    void unknownTargetIsANoOp() {
        Fx f = createFixture();
        assignAllAndAddMaterial(f);
        Map<Long, LineState> baseline = lineStates(f.projectId);

        assignmentService.applyLabourPriceAdjustment(
                f.projectId, LabourPriceAdjustmentScope.WORK_ITEM, Long.MAX_VALUE, new BigDecimal("25"));
        assignmentService.applyLabourPriceAdjustment(
                f.projectId, LabourPriceAdjustmentScope.WORK_CATEGORY, Long.MAX_VALUE, new BigDecimal("25"));

        assertThat(lineStates(f.projectId)).isEqualTo(baseline);
    }

    // ------------------------------------------------------------------------------------------
    // 3.5: preview / save of staged sets made only of existing kinds
    // ------------------------------------------------------------------------------------------

    /** Independent model of a staged set of ASSIGN / UNASSIGN / SET_QUANTITY edits. */
    private record Cell(boolean assigned, BigDecimal volume) { }

    @Test
    void previewOfExistingKindsMatchesTheModelEqualsSaveAndPersistsNothing() {
        Fx f = createFixture();
        List<Long> works = List.of(f.a1, f.a2, f.b1);
        List<Long> rooms = List.of(f.room1, f.room2);
        Map<Long, BigDecimal> price = Map.of(f.a1, PRICE_A1, f.a2, PRICE_A2, f.b1, PRICE_B1);
        Map<Long, BigDecimal> area = Map.of(f.room1, AREA_1, f.room2, AREA_2);

        List<List<Integer>> sets = Arbitraries.integers().between(0, 2 * works.size() * rooms.size() * 3 - 1)
                .list().ofMinSize(0).ofMaxSize(10).list().ofSize(SET_SAMPLES).sample();

        for (List<Integer> codes : sets) {
            List<StagedEdit> edits = new ArrayList<>();
            Map<String, Cell> model = new HashMap<>();
            for (int code : codes) {
                int op = code % 3;
                int rest = code / 3;
                Long work = works.get(rest % works.size());
                Long room = rooms.get((rest / works.size()) % rooms.size());
                String key = work + "/" + room;
                switch (op) {
                    case 0 -> {
                        edits.add(StagedEdit.assign(work, room, null));
                        model.putIfAbsent(key, new Cell(true, area.get(room)));
                    }
                    case 1 -> {
                        edits.add(StagedEdit.unassign(work, room));
                        model.remove(key);
                    }
                    default -> {
                        BigDecimal qty = BigDecimal.valueOf(5 + (code % 7) * 3L, 0);
                        edits.add(new StagedEdit(EstimateAssignmentService.EditKind.SET_QUANTITY, work, room,
                                null, null, null, null, null, null, qty));
                        if (model.containsKey(key)) {
                            model.put(key, new Cell(true, qty));
                        }
                    }
                }
            }

            EstimateMatrixDto preview = assignmentService.previewStagedEdits(f.projectId, edits, true);

            for (Long work : works) {
                for (Long room : rooms) {
                    Cell expected = model.get(work + "/" + room);
                    CellDto cell = findCell(preview, work, room);
                    boolean assigned = cell != null && cell.assigned();
                    assertThat(assigned).as("assigned work=%s room=%s edits=%s", work, room, edits)
                            .isEqualTo(expected != null);
                    if (expected != null) {
                        assertThat(cell.volume()).as("volume work=%s room=%s", work, room)
                                .isEqualByComparingTo(expected.volume());
                        assertThat(cell.labour()).as("labour work=%s room=%s", work, room)
                                .isEqualByComparingTo(price.get(work).multiply(expected.volume()));
                    }
                }
            }
            assertThat(lineStates(f.projectId)).as("preview must persist nothing").isEmpty();

            EstimateMatrixDto saved = assignmentService.saveAndAssemble(f.projectId, edits);
            for (Long work : works) {
                for (Long room : rooms) {
                    CellDto p = findCell(preview, work, room);
                    CellDto s = findCell(saved, work, room);
                    assertThat(s != null && s.assigned()).isEqualTo(p != null && p.assigned());
                    if (p != null && p.assigned()) {
                        assertThat(s.volume()).isEqualByComparingTo(p.volume());
                        assertThat(s.labour()).isEqualByComparingTo(p.labour());
                    }
                }
            }
            lineStates(f.projectId).forEach((work, st) ->
                    assertThat(st.override()).as("save keeps override at the original price")
                            .isEqualByComparingTo(st.unitPrice()));

            // Reset to an empty estimate for the next sample.
            List<StagedEdit> clear = new ArrayList<>();
            for (Long work : works) {
                for (Long room : rooms) {
                    clear.add(StagedEdit.unassign(work, room));
                }
            }
            assignmentService.saveAndAssemble(f.projectId, clear);
            assertThat(lineStates(f.projectId)).isEmpty();
        }
    }

    private static CellDto findCell(EstimateMatrixDto matrix, Long workItemId, Long roomId) {
        return matrix.groups().stream()
                .flatMap(group -> group.rows().stream())
                .flatMap(row -> row.cells().stream())
                .filter(cell -> cell != null && workItemId.equals(cell.workItemId()) && roomId.equals(cell.roomId()))
                .findFirst()
                .orElse(null);
    }
}
