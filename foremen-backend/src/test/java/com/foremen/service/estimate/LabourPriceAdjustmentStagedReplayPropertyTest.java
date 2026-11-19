package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import com.foremen.service.estimate.EstimateAssignmentService.EditKind;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;
import com.foremen.service.estimate.matrix.CellDto;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-05-05c Property 1 (Expected Behavior) for the staged {@link EditKind#ADJUST_LABOUR_PRICE} edit.
 * For random {@code pct} (scale 0-2, negative and 0 included), random scope / target and a random prior
 * staged set of existing kinds (assign, unassign, set quantity), with the adjustment appended last:
 *
 * <ol>
 *   <li>every in-scope cell (persisted or created by an earlier staged edit) has labour
 *       {@code round2(unitPrice × (1 + pct/100)) × volume};</li>
 *   <li>every out-of-scope cell, and the assigned / volume result of every other edit, equals the replay
 *       without the adjustment;</li>
 *   <li>Save of the staged set with the adjustment persists the same overrides as Save of the set followed by
 *       the direct {@code applyLabourPriceAdjustment} call; {@code unit_price} stays untouched;</li>
 *   <li>preview with an adjustment writes nothing (also when persisted lines already exist).</li>
 * </ol>
 *
 * <p>Inputs are drawn from a fixed-seed {@link Random} (seed printed in the assertion descriptions) inside
 * JUnit tests, so failures are reproducible: the cases share a Testcontainers-backed Spring context, which the
 * jqwik engine does not inject into (the preservation test samples arbitraries the same way, unseeded).
 *
 * <p>Repeatability: every fixture row carries a per-run id and {@link #cleanUp()} deletes it.
 *
 * <p><b>Validates: Requirements 2.1, 2.2, 2.4, 3.1, 3.5, 3.7</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class LabourPriceAdjustmentStagedReplayPropertyTest {

    private static final long SEED = 505_05_03L;
    private static final int SAMPLES = 25;
    private static final BigDecimal PRICE_A1 = new BigDecimal("133.37");
    private static final BigDecimal PRICE_A2 = new BigDecimal("200.00");
    private static final BigDecimal PRICE_B1 = new BigDecimal("99.99");
    private static final BigDecimal AREA_1 = new BigDecimal("20.00");
    private static final BigDecimal AREA_2 = new BigDecimal("12.50");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

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

            ProjectEntity project = new ProjectEntity();
            project.setName("Labour Adj Staged Replay " + runId);
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

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private record LineState(BigDecimal unitPrice, BigDecimal override) { }

    private Map<Long, LineState> lineStates(Long projectId) {
        return tx().execute(status -> {
            Map<Long, LineState> out = new HashMap<>();
            List<Object[]> rows = entityManager.createQuery(
                            "select l.workItem.id, l.unitPrice, l.unitPriceOverride "
                                    + "from EstimateLineEntity l where l.estimate.project.id = :p", Object[].class)
                    .setParameter("p", projectId).getResultList();
            for (Object[] r : rows) {
                out.put((Long) r[0], new LineState((BigDecimal) r[1], (BigDecimal) r[2]));
            }
            return out;
        });
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

    private static Long targetFor(Fx f, LabourPriceAdjustmentScope scope, Random rnd) {
        return switch (scope) {
            case PROJECT -> null;
            case WORK_CATEGORY -> rnd.nextBoolean() ? f.categoryA : f.categoryB;
            case WORK_ITEM -> List.of(f.a1, f.a2, f.b1).get(rnd.nextInt(3));
        };
    }

    /** Signed percentage, scale 0-2, from jqwik arbitraries; zero and the -100 edge appear regularly. */
    private static BigDecimal samplePct(Random rnd, int i) {
        if (i % 9 == 0) {
            return BigDecimal.ZERO;
        }
        if (i % 9 == 1) {
            return new BigDecimal("-100");
        }
        if (rnd.nextBoolean()) {
            return BigDecimal.valueOf(-9500 + rnd.nextInt(39501), 2);
        }
        return BigDecimal.valueOf(-95 + rnd.nextInt(396));
    }

    /** Random prior staged set of ASSIGN / UNASSIGN / SET_QUANTITY edits over the 3 works x 2 rooms grid. */
    private static List<StagedEdit> randomPriorSet(Fx f, Random rnd) {
        List<Long> works = List.of(f.a1, f.a2, f.b1);
        List<Long> rooms = List.of(f.room1, f.room2);
        int size = rnd.nextInt(11);
        List<StagedEdit> edits = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Long work = works.get(rnd.nextInt(works.size()));
            Long room = rooms.get(rnd.nextInt(rooms.size()));
            switch (rnd.nextInt(3)) {
                case 0 -> edits.add(StagedEdit.assign(work, room, null));
                case 1 -> edits.add(StagedEdit.unassign(work, room));
                default -> edits.add(new StagedEdit(EditKind.SET_QUANTITY, work, room,
                        null, null, null, null, null, null, BigDecimal.valueOf(5 + rnd.nextInt(7) * 3L)));
            }
        }
        return edits;
    }

    private static List<StagedEdit> withAdjustment(
            List<StagedEdit> prior, LabourPriceAdjustmentScope scope, Long targetId, BigDecimal pct) {
        List<StagedEdit> all = new ArrayList<>(prior);
        all.add(StagedEdit.adjustLabourPrice(scope, targetId, pct));
        return all;
    }

    private static CellDto findCell(EstimateMatrixDto matrix, Long workItemId, Long roomId) {
        return matrix.groups().stream()
                .flatMap(group -> group.rows().stream())
                .flatMap(row -> row.cells().stream())
                .filter(cell -> cell != null && workItemId.equals(cell.workItemId()) && roomId.equals(cell.roomId()))
                .findFirst()
                .orElse(null);
    }

    private void clearAll(Fx f) {
        List<StagedEdit> clear = new ArrayList<>();
        for (Long work : List.of(f.a1, f.a2, f.b1)) {
            for (Long room : List.of(f.room1, f.room2)) {
                clear.add(StagedEdit.unassign(work, room));
            }
        }
        assignmentService.saveAndAssemble(f.projectId, clear);
        assertThat(lineStates(f.projectId)).as("fixture reset must leave no lines").isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Properties
    // ------------------------------------------------------------------------------------------

    @Test
    void stagedAdjustmentChangesOnlyInScopeLabourAndLeavesEverythingElseUnchanged() {
        Fx f = createFixture();
        Random rnd = new Random(SEED);
        Map<Long, BigDecimal> price = Map.of(f.a1, PRICE_A1, f.a2, PRICE_A2, f.b1, PRICE_B1);
        LabourPriceAdjustmentScope[] scopes = LabourPriceAdjustmentScope.values();

        for (int i = 0; i < SAMPLES; i++) {
            LabourPriceAdjustmentScope scope = scopes[i % scopes.length];
            Long targetId = targetFor(f, scope, rnd);
            BigDecimal pct = samplePct(rnd, i);
            List<StagedEdit> prior = randomPriorSet(f, rnd);
            List<StagedEdit> adjusted = withAdjustment(prior, scope, targetId, pct);
            String ctx = "seed=" + SEED + " i=" + i + " scope=" + scope + " target=" + targetId + " pct=" + pct
                    + " prior=" + prior;

            EstimateMatrixDto base = assignmentService.previewStagedEdits(f.projectId, prior, true);
            EstimateMatrixDto adj = assignmentService.previewStagedEdits(f.projectId, adjusted, true);

            for (Long work : List.of(f.a1, f.a2, f.b1)) {
                for (Long room : List.of(f.room1, f.room2)) {
                    CellDto b = findCell(base, work, room);
                    CellDto a = findCell(adj, work, room);
                    boolean baseAssigned = b != null && b.assigned();
                    assertThat(a != null && a.assigned()).as("assigned work=%s room=%s %s", work, room, ctx)
                            .isEqualTo(baseAssigned);
                    if (!baseAssigned) {
                        continue;
                    }
                    assertThat(a.volume()).as("volume work=%s room=%s %s", work, room, ctx)
                            .isEqualByComparingTo(b.volume());
                    BigDecimal unit = inScope(f, scope, targetId, work)
                            ? round2Adjusted(price.get(work), pct)
                            : price.get(work);
                    assertThat(a.labour()).as("labour work=%s room=%s %s", work, room, ctx)
                            .isEqualByComparingTo(unit.multiply(b.volume()));
                    if (!inScope(f, scope, targetId, work)) {
                        assertThat(a.labour()).as("out-of-scope labour equals replay without adjustment %s", ctx)
                                .isEqualByComparingTo(b.labour());
                    }
                }
            }
            assertThat(lineStates(f.projectId)).as("preview must persist nothing: %s", ctx).isEmpty();
        }
    }

    @Test
    void savedStagedAdjustmentEqualsDirectPathOverridesAndKeepsUnitPrice() {
        Fx f = createFixture();
        Random rnd = new Random(SEED + 1);
        LabourPriceAdjustmentScope[] scopes = LabourPriceAdjustmentScope.values();

        for (int i = 0; i < SAMPLES; i++) {
            LabourPriceAdjustmentScope scope = scopes[i % scopes.length];
            Long targetId = targetFor(f, scope, rnd);
            BigDecimal pct = samplePct(rnd, i);
            List<StagedEdit> prior = randomPriorSet(f, rnd);
            String ctx = "seed=" + (SEED + 1) + " i=" + i + " scope=" + scope + " target=" + targetId + " pct=" + pct
                    + " prior=" + prior;

            // Direct path: Save the prior set, then the legacy immediate write.
            assignmentService.saveAndAssemble(f.projectId, prior);
            assignmentService.applyLabourPriceAdjustment(f.projectId, scope, targetId, pct);
            Map<Long, LineState> direct = lineStates(f.projectId);
            clearAll(f);

            // Staged path: the same set with the adjustment replayed in order and persisted by Save.
            assignmentService.saveAndAssemble(f.projectId, withAdjustment(prior, scope, targetId, pct));
            Map<Long, LineState> staged = lineStates(f.projectId);

            assertThat(staged.keySet()).as("same lines %s", ctx).isEqualTo(direct.keySet());
            staged.forEach((work, st) -> {
                LineState d = direct.get(work);
                assertThat(st.override()).as("override equals direct path work=%s %s", work, ctx)
                        .isEqualByComparingTo(d.override());
                BigDecimal expected = inScope(f, scope, targetId, work)
                        ? round2Adjusted(st.unitPrice(), pct)
                        : st.unitPrice();
                assertThat(st.override()).as("override equals formula work=%s %s", work, ctx)
                        .isEqualByComparingTo(expected);
                assertThat(st.unitPrice()).as("unit_price untouched work=%s %s", work, ctx)
                        .isEqualByComparingTo(d.unitPrice());
            });
            clearAll(f);
        }
    }

    @Test
    void previewWithAdjustmentLeavesPersistedOverridesUnchanged() {
        Fx f = createFixture();
        Random rnd = new Random(SEED + 2);
        assignmentService.saveAndAssemble(f.projectId, List.of(
                StagedEdit.assign(f.a1, f.room1, null),
                StagedEdit.assign(f.a2, f.room1, null),
                StagedEdit.assign(f.b1, f.room2, null)));
        Map<Long, LineState> baseline = lineStates(f.projectId);
        assertThat(baseline).hasSize(3);
        LabourPriceAdjustmentScope[] scopes = LabourPriceAdjustmentScope.values();

        for (int i = 0; i < SAMPLES; i++) {
            LabourPriceAdjustmentScope scope = scopes[i % scopes.length];
            Long targetId = targetFor(f, scope, rnd);
            BigDecimal pct = samplePct(rnd, i);
            assignmentService.previewStagedEdits(f.projectId,
                    withAdjustment(List.of(), scope, targetId, pct), true);
            assertThat(lineStates(f.projectId))
                    .as("seed=%s i=%s scope=%s target=%s pct=%s", SEED + 2, i, scope, targetId, pct)
                    .isEqualTo(baseline);
        }
    }
}
