package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.estimate.LabourPriceAdjustmentScope;
import com.foremen.service.estimateai.AiJobContext;
import com.foremen.service.estimateai.AssignRejected;
import com.foremen.service.estimateai.AssignWorkCommand;
import com.foremen.service.estimateai.AssignWorkResult;
import com.foremen.service.estimateai.AssignWorkService;
import com.foremen.service.estimateai.WarningCodes;

/**
 * FOR-05-04 task 9.2 - {@link AssignWorkService} against the real schema: every call is committed and visible
 * at once, every assigned cell carries its material lines and its {@code ai_meta}, a volume beyond the
 * tolerance becomes a manual override, a coefficient changes the price only when stated, a repeated
 * {@code (work, room)} updates the cell, and rejections write nothing.
 *
 * <p>Repeatability: generator strategy - every test creates its own project, rooms and job under a per-instance
 * run id; the single catalog work (with one construction material norm) is created once under a fixed code.
 *
 * <p>Validates: Requirements 24.2, 24.3, 24.4, 24.5, 24.6, 24.7, 24.12, 6.2, 6.4
 */
@TestPropertySource(properties = {
        "foremen.estimate-ai.enabled=true",
        "foremen.estimate-ai.llm.stub=true"
})
class AssignWorkServiceIT extends RoomsAutoimportItBase {

    private static final String WORK_CODE = "000-ASSIGN-WORK";
    private static final String MATERIAL_TYPE_CODE = "000-ASSIGN-MAT";

    @Autowired private AssignWorkService service;
    @Autowired private RoomsImportJobDao jobDao;
    @Autowired private CurrencyDao currencyDao;

    private long workId;

    @BeforeEach
    void seedCatalog() {
        if (count("SELECT COUNT(*) FROM work_items WHERE code = ?", WORK_CODE) == 0) {
            tx().executeWithoutResult(s -> {
                MeasurementUnitEntity unit = unit("m2");
                WorkCategoryEntity category = new WorkCategoryEntity();
                category.setCode("000-ASSIGN");
                // Sorts last: the stub planner of the EstimateAi*IT classes picks the first catalog work.
                category.setOrderNo(1_000_000);
                category.setNameRU("Assign IT");
                category.setNamePL("Assign IT");
                category.setActive(true);
                entityManager.persist(category);
                WorkItemEntity item = new WorkItemEntity();
                item.setWorkCategory(category);
                item.setUnit(unit);
                item.setCode(WORK_CODE);
                item.setNameRU("Assign IT work");
                item.setNamePL("Assign IT work");
                item.setActive(true);
                entityManager.persist(item);
                CurrencyEntity pln = currencyDao.findByCode("PLN").orElseGet(() -> {
                    CurrencyEntity c = new CurrencyEntity();
                    c.setCode("PLN");
                    c.setSymbol("zl");
                    c.setNameRU("Zloty");
                    c.setNamePL("Zloty");
                    c.setActive(true);
                    return currencyDao.save(c);
                });
                WorkPriceEntity price = new WorkPriceEntity();
                price.setWorkItem(item);
                price.setCurrency(pln);
                price.setNetPrice(new BigDecimal("40.00"));
                entityManager.persist(price);
                ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
                type.setCode(MATERIAL_TYPE_CODE);
                type.setNameRU("Assign IT material");
                type.setNamePL("Assign IT material");
                type.setActive(true);
                entityManager.persist(type);
                WorkMaterialConsumptionEntity c = new WorkMaterialConsumptionEntity();
                c.setWorkItem(item);
                c.setMaterialUnit(unit);
                c.setBranch(ConsumptionBranch.construction);
                c.setConstructionMaterialType(type);
                c.setNormQty(new BigDecimal("2"));
                c.setConsumptionBasis(ConsumptionBasis.PER_UNIT);
                c.setSourceType("TEST");
                c.setSourceDoc("test");
                c.setSourceRef("test");
                entityManager.persist(c);
            });
        }
        workId = jdbc.queryForObject("SELECT id FROM work_items WHERE code = ?", Long.class, WORK_CODE);
        UserEntity admin = user("ADMIN");
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(String.valueOf(admin.getId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private MeasurementUnitEntity unit(String code) {
        return entityManager.createQuery("select u from MeasurementUnitEntity u where lower(u.code) = :c",
                MeasurementUnitEntity.class).setParameter("c", code).getResultStream().findFirst()
                .orElseGet(() -> {
                    MeasurementUnitEntity u = new MeasurementUnitEntity();
                    u.setCode(code);
                    u.setNameRU(code);
                    u.setNamePL(code);
                    u.setActive(true);
                    entityManager.persist(u);
                    return u;
                });
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private long job(long projectId, RoomsImportStatus status) {
        UserEntity admin = user("ADMIN");
        return tx().execute(s -> {
            RoomsImportJobEntity job = new RoomsImportJobEntity();
            job.setProject(entityManager.getReference(ProjectEntity.class, projectId));
            job.setCreator(entityManager.getReference(UserEntity.class, admin.getId()));
            job.setStatus(status);
            job.setMode("ESTIMATE");
            job.setInputs(new ArrayList<>());
            job.setStartedAt(LocalDateTime.now());
            return jobDao.save(job).getId();
        });
    }

    private AssignWorkCommand cmd(List<Long> rooms, String volume, List<AssignWorkCommand.Coefficient> coeffs) {
        return new AssignWorkCommand(workId, rooms, volume == null ? null : new BigDecimal(volume),
                volume == null ? null : "plan states it", "walls need it", "CONFIRMED", new BigDecimal("0.9"),
                "plan.pdf, p.2", "stated markup", coeffs);
    }

    private int cells(long projectId) {
        return count("SELECT COUNT(*) FROM estimate_line_room_qty q JOIN estimate_lines l ON l.id = q.line_id "
                + "JOIN estimates e ON e.id = l.estimate_id WHERE e.project_id = ? AND l.work_item_id = ?",
                projectId, workId);
    }

    private int lines(long projectId) {
        return count("SELECT COUNT(*) FROM estimate_lines l JOIN estimates e ON e.id = l.estimate_id "
                + "WHERE e.project_id = ? AND l.work_item_id = ?", projectId, workId);
    }

    @Test
    @DisplayName("a call is committed and visible: cells, formula volumes, material lines, ai_meta, counters, log")
    void committedAndVisible() {
        long projectId = createProject("assign-visible");
        long r1 = seedRoom(projectId, "A", "20.00");
        long r2 = seedRoom(projectId, "B", "10.00");
        long jobId = job(projectId, RoomsImportStatus.ASSIGNING);

        AssignWorkResult result = service.assign(new AiJobContext(jobId, projectId), cmd(List.of(r1, r2), null, List.of()));

        assertThat(result.cells()).hasSize(2).allMatch(AssignWorkResult.Cell::created);
        assertThat(cells(projectId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT q.quantity FROM estimate_line_room_qty q WHERE q.room_id = ?",
                BigDecimal.class, r1)).isEqualByComparingTo("20");
        // materials seeded for EVERY assigned cell
        assertThat(count("SELECT COUNT(*) FROM estimate_line_room_materials m JOIN estimate_line_room_qty q "
                + "ON q.id = m.room_qty_id WHERE q.room_id IN (?, ?)", r1, r2)).isEqualTo(2);
        assertThat(result.cells()).allMatch(c -> c.materialLines() == 1);
        // annotation written in the same transaction
        assertThat(count("SELECT COUNT(*) FROM estimate_line_room_qty q WHERE q.room_id IN (?, ?) "
                + "AND q.ai_meta IS NOT NULL AND q.ai_meta ->> 'origin' = 'JOB' "
                + "AND q.ai_meta -> 'materials' ->> 'lines' = '1' AND q.ai_meta ->> 'rationale' = 'walls need it'",
                r1, r2)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT works_done FROM rooms_import_jobs WHERE id = ?", Integer.class, jobId))
                .isEqualTo(1);
        String log = jdbc.queryForObject("SELECT action_log::text FROM rooms_import_jobs WHERE id = ?",
                String.class, jobId);
        assertThat(log).contains("WORK_ASSIGNED").contains("MATERIALS_SEEDED");
    }

    @Test
    @DisplayName("a volume beyond the tolerance is a manual override with its reason; within it the formula wins")
    void volumeOverride() {
        long projectId = createProject("assign-volume");
        long r1 = seedRoom(projectId, "A", "20.00");
        long r2 = seedRoom(projectId, "B", "20.00");
        long jobId = job(projectId, RoomsImportStatus.ASSIGNING);
        AiJobContext ctx = new AiJobContext(jobId, projectId);

        service.assign(ctx, cmd(List.of(r1), "30", List.of()));
        service.assign(ctx, cmd(List.of(r2), "20.5", List.of())); // 2.5% -> formula volume

        assertThat(jdbc.queryForObject("SELECT quantity FROM estimate_line_room_qty WHERE room_id = ?",
                BigDecimal.class, r1)).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("SELECT volume_overridden FROM estimate_line_room_qty WHERE room_id = ?",
                Boolean.class, r1)).isTrue();
        assertThat(jdbc.queryForObject("SELECT ai_meta -> 'volume' ->> 'source' FROM estimate_line_room_qty "
                + "WHERE room_id = ?", String.class, r1)).isEqualTo("MODEL_OVERRIDE");
        assertThat(jdbc.queryForObject("SELECT ai_meta -> 'volume' ->> 'adjustmentReason' "
                + "FROM estimate_line_room_qty WHERE room_id = ?", String.class, r1)).isEqualTo("plan states it");
        assertThat(jdbc.queryForObject("SELECT quantity FROM estimate_line_room_qty WHERE room_id = ?",
                BigDecimal.class, r2)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT volume_overridden FROM estimate_line_room_qty WHERE room_id = ?",
                Boolean.class, r2)).isFalse();
    }

    @Test
    @DisplayName("a labour coefficient changes the price only when stated")
    void coefficientOnlyWhenStated() {
        long projectId = createProject("assign-coeff");
        long r1 = seedRoom(projectId, "A", "20.00");
        long jobId = job(projectId, RoomsImportStatus.ASSIGNING);
        AiJobContext ctx = new AiJobContext(jobId, projectId);

        service.assign(ctx, cmd(List.of(r1), null, List.of()));
        assertThat(jdbc.queryForObject("SELECT l.unit_price_override FROM estimate_lines l JOIN estimates e "
                + "ON e.id = l.estimate_id WHERE e.project_id = ? AND l.work_item_id = ?", BigDecimal.class,
                projectId, workId)).isEqualByComparingTo("40");
        assertThat(jdbc.queryForObject("SELECT ai_meta -> 'price' IS NULL FROM estimate_line_room_qty "
                + "WHERE room_id = ?", Boolean.class, r1)).isTrue();

        service.assign(ctx, cmd(List.of(r1), null,
                List.of(new AssignWorkCommand.Coefficient(LabourPriceAdjustmentScope.WORK_ITEM, workId,
                        new BigDecimal("10")))));
        assertThat(jdbc.queryForObject("SELECT l.unit_price_override FROM estimate_lines l JOIN estimates e "
                + "ON e.id = l.estimate_id WHERE e.project_id = ? AND l.work_item_id = ?", BigDecimal.class,
                projectId, workId)).isEqualByComparingTo("44");
        assertThat(jdbc.queryForObject("SELECT ai_meta -> 'price' ->> 'reason' FROM estimate_line_room_qty "
                + "WHERE room_id = ?", String.class, r1)).isEqualTo("stated markup");
    }

    @Test
    @DisplayName("repeating a (work, room) updates the cell: one line, one cell, last volume wins, no extra materials")
    void idempotent() {
        long projectId = createProject("assign-idem");
        long r1 = seedRoom(projectId, "A", "20.00");
        long jobId = job(projectId, RoomsImportStatus.ASSIGNING);
        AiJobContext ctx = new AiJobContext(jobId, projectId);

        service.assign(ctx, cmd(List.of(r1), "30", List.of()));
        AssignWorkResult second = service.assign(ctx, cmd(List.of(r1), "40", List.of()));
        assertThat(second.cells().get(0).created()).isFalse();
        assertThat(lines(projectId)).isEqualTo(1);
        assertThat(cells(projectId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM estimate_line_room_qty WHERE room_id = ?",
                BigDecimal.class, r1)).isEqualByComparingTo("40");

        service.assign(ctx, cmd(List.of(r1), null, List.of())); // back to the formula volume
        assertThat(jdbc.queryForObject("SELECT quantity FROM estimate_line_room_qty WHERE room_id = ?",
                BigDecimal.class, r1)).isEqualByComparingTo("20");
        assertThat(count("SELECT COUNT(*) FROM estimate_line_room_materials m JOIN estimate_line_room_qty q "
                + "ON q.id = m.room_qty_id WHERE q.room_id = ?", r1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT works_done FROM rooms_import_jobs WHERE id = ?", Integer.class, jobId))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("rejections are typed and write nothing")
    void rejections() {
        long projectId = createProject("assign-reject");
        long r1 = seedRoom(projectId, "A", "20.00");
        long foreignRoom = seedRoom(createProject("assign-reject-other"), "X", "5.00");
        long jobId = job(projectId, RoomsImportStatus.ASSIGNING);
        AiJobContext ctx = new AiJobContext(jobId, projectId);

        assertThatThrownBy(() -> service.assign(ctx, new AssignWorkCommand(-1L, List.of(r1), null, null, "r",
                "CONFIRMED", null, null, null, List.of())))
                .isInstanceOfSatisfying(AssignRejected.class, e -> assertThat(e.code())
                        .isEqualTo(WarningCodes.UNKNOWN_CODE));
        assertThatThrownBy(() -> service.assign(ctx, new AssignWorkCommand(workId, List.of(r1), null, null, " ",
                "CONFIRMED", null, null, null, List.of())))
                .isInstanceOfSatisfying(AssignRejected.class, e -> assertThat(e.code())
                        .isEqualTo(WarningCodes.EDIT_REJECTED));
        assertThatThrownBy(() -> service.assign(ctx, cmd(List.of(r1), "-5", List.of())))
                .isInstanceOfSatisfying(AssignRejected.class, e -> assertThat(e.code())
                        .isEqualTo(WarningCodes.EDIT_REJECTED));
        assertThatThrownBy(() -> service.assign(ctx, cmd(List.of(r1, foreignRoom), null, List.of())))
                .isInstanceOfSatisfying(AssignRejected.class, e -> assertThat(e.params())
                        .containsEntry("reason", WarningCodes.UNKNOWN_ROOM));

        assertThat(cells(projectId)).isZero();
        assertThat(jdbc.queryForObject("SELECT works_done FROM rooms_import_jobs WHERE id = ?", Integer.class, jobId))
                .isNull();
    }

    @Test
    @DisplayName("a finished job, or a context of another project, cannot write")
    void onlyTheActiveOwningJobWrites() {
        long projectId = createProject("assign-owner");
        long r1 = seedRoom(projectId, "A", "20.00");
        long jobId = job(projectId, RoomsImportStatus.COMPLETED); // one job row per project

        assertThatThrownBy(() -> service.assign(new AiJobContext(jobId, projectId), cmd(List.of(r1), null, List.of())))
                .isInstanceOfSatisfying(AssignRejected.class, e -> assertThat(e.params())
                        .containsEntry("reason", "JOB_NOT_ACTIVE"));

        jdbc.update("UPDATE rooms_import_jobs SET status = 'ASSIGNING' WHERE id = ?", jobId);
        long active = jobId;
        long otherProject = createProject("assign-owner-other");
        assertThatThrownBy(() -> service.assign(new AiJobContext(active, otherProject),
                cmd(List.of(r1), null, List.of())))
                .isInstanceOf(AssignRejected.class);
        assertThat(cells(projectId)).isZero();
    }
}
