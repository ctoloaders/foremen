package com.foremen.service.companyschedule;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.controller.model.companyschedule.BarKind;
import com.foremen.controller.model.companyschedule.CompanyScheduleQuery;
import com.foremen.controller.model.companyschedule.CompanyScheduleView;
import com.foremen.controller.model.companyschedule.MemberRow;
import com.foremen.controller.model.companyschedule.ProjectedBar;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-07-02 bug-report Round 1 #2: a project with a start date but no end date dates its worker bars,
 * so its foreman span must be dated too (start → latest worker-bar end) and must survive a Date_Window
 * instead of silently dropping the foreman's row.
 */
class CrossProjectScheduleProjectorForemanSpanTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 20);
    private static final long FOREMAN_ID = 100L;
    private static final long WORKER_ID = 200L;

    /** A project with a start date, no end date, one foreman and a worker bar of 8 calendar days. */
    private static ProjectInput startOnlyProject() {
        // half-days 1..16 → calendar days 1..8 → 2026-10-20 .. 2026-10-27
        WorkerBarInput worker = new WorkerBarInput(
                WORKER_ID, "Worker", "WORKER", 1L, "Кат", "Kat", 1, 16);
        return new ProjectInput(1L, "P1", START, null,
                List.of(worker), List.of(new ForemanInput(FOREMAN_ID, "Foreman")));
    }

    private static MemberRow rowOf(CompanyScheduleView view, long userId) {
        return view.rows().stream().filter(r -> r.userId() == userId).findFirst().orElse(null);
    }

    @Test
    @DisplayName("start-only project: the foreman span runs from the start to the latest worker-bar end")
    void startOnlyProjectDatesTheForemanSpan() {
        CompanyScheduleView view = CrossProjectScheduleProjector.project(
                List.of(startOnlyProject()), null, false);

        MemberRow foreman = rowOf(view, FOREMAN_ID);
        assertThat(foreman).isNotNull();
        ProjectedBar span = foreman.bars().get(0);
        assertThat(span.kind()).isEqualTo(BarKind.FOREMAN_SPAN);
        assertThat(span.unanchored()).isFalse();
        assertThat(span.startDate()).isEqualTo(START);
        assertThat(span.endDate()).isEqualTo(LocalDate.of(2026, 10, 27));
    }

    @Test
    @DisplayName("start-only project: the foreman row survives a Date_Window")
    void startOnlyProjectForemanSurvivesDateWindow() {
        CompanyScheduleQuery window = new CompanyScheduleQuery(
                null, null, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        CompanyScheduleView view = CrossProjectScheduleProjector.project(
                List.of(startOnlyProject()), window, false);

        assertThat(rowOf(view, FOREMAN_ID)).isNotNull();
        assertThat(rowOf(view, WORKER_ID)).isNotNull();
    }

    @Test
    @DisplayName("derived span: both dates present → [start, end]; start only → start..latest or start; no start → undatable")
    void derivedSpanDates() {
        LocalDate end = LocalDate.of(2026, 12, 1);
        assertThat(CrossProjectScheduleProjector.foremanSpanDates(START, end, null))
                .containsExactly(START, end);
        assertThat(CrossProjectScheduleProjector.foremanSpanDates(START, null, LocalDate.of(2026, 11, 5)))
                .containsExactly(START, LocalDate.of(2026, 11, 5));
        assertThat(CrossProjectScheduleProjector.foremanSpanDates(START, null, null))
                .containsExactly(START, START);
        assertThat(CrossProjectScheduleProjector.foremanSpanDates(START, null, START.minusDays(3)))
                .containsExactly(START, START);
        assertThat(CrossProjectScheduleProjector.foremanSpanDates(null, end, end))
                .containsExactly(null, null);
    }
}
