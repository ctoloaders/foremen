package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.foremen.controller.model.companyschedule.BarKind;
import com.foremen.controller.model.companyschedule.CompanyScheduleQuery;
import com.foremen.controller.model.companyschedule.CompanyScheduleView;
import com.foremen.controller.model.companyschedule.MemberRow;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.service.companyschedule.CrossProjectScheduleProjector;
import com.foremen.service.companyschedule.ForemanInput;
import com.foremen.service.companyschedule.ProjectInput;
import com.foremen.service.companyschedule.WorkerBarInput;

/**
 * FOR-07-01 additional tasks 1–4 — the staffing-gantt redesign rules: a manager standing in for a
 * missing foreman yields a flagged FOREMAN_SPAN, rows read as the user's main role and are ordered
 * managers → foremen → workers, a MANAGER Role_Filter keeps the stand-in spans, and the project
 * inclusion rule (ACTIVE or ended within two weeks; window intersection when windowed).
 */
class CrossProjectScheduleRedesignTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 12, 31);

    @Test
    void managerActingAsForemanSpanIsFlaggedAndRowReadsAsManager() {
        ProjectInput project = new ProjectInput(1L, "Alpha", START, END, List.of(),
                List.of(new ForemanInput(10L, "Marek", true)), Map.of(10L, "MANAGER"));

        CompanyScheduleView view = CrossProjectScheduleProjector.project(List.of(project), null, false);

        assertThat(view.rows()).singleElement().satisfies(row -> {
            assertThat(row.companyRole()).isEqualTo("MANAGER");
            assertThat(row.bars()).singleElement().satisfies(bar -> {
                assertThat(bar.kind()).isEqualTo(BarKind.FOREMAN_SPAN);
                assertThat(bar.managerActingAsForeman()).isTrue();
            });
        });
    }

    @Test
    void realForemanSpanIsNotFlagged() {
        ProjectInput project = new ProjectInput(1L, "Alpha", START, END, List.of(),
                List.of(new ForemanInput(20L, "Tomasz")), Map.of(20L, "FOREMAN"));

        MemberRow row = CrossProjectScheduleProjector.project(List.of(project), null, false).rows().get(0);

        assertThat(row.companyRole()).isEqualTo("FOREMAN");
        assertThat(row.bars().get(0).managerActingAsForeman()).isFalse();
    }

    @Test
    void rowsAreOrderedManagersThenForemenThenWorkersAndUseTheMainRole() {
        // Project A has a foreman (Zenon); project B has none, so its manager (Yvonne) stands in.
        // Adam works in A but is a MANAGER member of B (no bar there) -> his main role is MANAGER.
        ProjectInput a = new ProjectInput(1L, "A", START, END,
                List.of(worker(30L, "Adam"), worker(40L, "Bartek")),
                List.of(new ForemanInput(50L, "Zenon")),
                Map.of(30L, "WORKER", 40L, "WORKER", 50L, "FOREMAN"));
        ProjectInput b = new ProjectInput(2L, "B", START, END, List.of(),
                List.of(new ForemanInput(60L, "Yvonne", true)),
                Map.of(60L, "MANAGER", 30L, "MANAGER"));

        List<MemberRow> rows = CrossProjectScheduleProjector.project(List.of(a, b), null, false).rows();

        assertThat(rows).extracting(MemberRow::userName, MemberRow::companyRole).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Adam", "MANAGER"),
                org.assertj.core.groups.Tuple.tuple("Yvonne", "MANAGER"),
                org.assertj.core.groups.Tuple.tuple("Zenon", "FOREMAN"),
                org.assertj.core.groups.Tuple.tuple("Bartek", "WORKER"));
    }

    @Test
    void managerRoleFilterKeepsStandInSpansAndForemanFilterToo() {
        ProjectInput b = new ProjectInput(2L, "B", START, END, List.of(),
                List.of(new ForemanInput(60L, "Yvonne", true)), Map.of(60L, "MANAGER"));

        for (String role : List.of("MANAGER", "FOREMAN")) {
            CompanyScheduleQuery query = new CompanyScheduleQuery(List.of(role), null, null, null, null);
            assertThat(CrossProjectScheduleProjector.project(List.of(b), query, false).rows()).hasSize(1);
        }
        CompanyScheduleQuery workers = new CompanyScheduleQuery(List.of("WORKER"), null, null, null, null);
        assertThat(CrossProjectScheduleProjector.project(List.of(b), workers, false).rows()).isEmpty();
    }

    @Test
    void defaultViewIncludesActiveAndRecentlyEndedProjectsOnly() {
        LocalDate today = LocalDate.of(2026, 11, 1);

        assertThat(CrossProjectScheduleService.isIncluded(
                project(ProjectStatus.ACTIVE, null, LocalDate.of(2025, 1, 1)), null, null, today)).isTrue();
        assertThat(CrossProjectScheduleService.isIncluded(
                project(ProjectStatus.COMPLETED, null, today.minusDays(13)), null, null, today)).isTrue();
        assertThat(CrossProjectScheduleService.isIncluded(
                project(ProjectStatus.COMPLETED, null, today.minusDays(14)), null, null, today)).isFalse();
        assertThat(CrossProjectScheduleService.isIncluded(
                project(ProjectStatus.DRAFT, null, today.plusMonths(2)), null, null, today)).isTrue();
        assertThat(CrossProjectScheduleService.isIncluded(
                project(ProjectStatus.DRAFT, null, null), null, null, today)).isFalse();
    }

    @Test
    void windowedViewIncludesProjectsIntersectingTheWindow() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);

        // An old, completed project active during the window is included when windowed.
        assertThat(CrossProjectScheduleService.isIncluded(project(ProjectStatus.COMPLETED,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 10)), from, to, today)).isTrue();
        assertThat(CrossProjectScheduleService.isIncluded(project(ProjectStatus.ACTIVE,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1)), from, to, today)).isFalse();
        assertThat(CrossProjectScheduleService.isIncluded(project(ProjectStatus.ACTIVE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28)), from, to, today)).isFalse();
        // Closed-interval touching counts; missing dates leave that side open.
        assertThat(CrossProjectScheduleService.isIncluded(project(ProjectStatus.ACTIVE,
                LocalDate.of(2026, 3, 31), null), from, to, today)).isTrue();
    }

    private static WorkerBarInput worker(long userId, String name) {
        return new WorkerBarInput(userId, name, "WORKER", 7L, "Плитка", "Płytki", 1, 4);
    }

    private static ProjectEntity project(ProjectStatus status, LocalDate start, LocalDate end) {
        ProjectEntity p = new ProjectEntity();
        p.setStatus(status);
        p.setStartDate(start);
        p.setEndDate(end);
        return p;
    }
}
