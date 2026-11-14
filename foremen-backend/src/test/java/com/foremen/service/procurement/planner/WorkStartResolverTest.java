package com.foremen.service.procurement.planner;

import com.foremen.dao.ProjectScheduleBarDao;
import com.foremen.dao.ProjectScheduleBarDao.EarliestBarRow;
import com.foremen.service.procurement.planner.WorkStartResolver.ProjectCategory;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Validates: Requirements 5.5 */
class WorkStartResolverTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 3, 2);

    private final ProjectScheduleBarDao dao = mock(ProjectScheduleBarDao.class);
    private final WorkStartResolver resolver = new WorkStartResolver(dao);

    @Test
    void halfDayConversion_firstAndSecondHalfOfDayShareDate() {
        assertThat(WorkStartResolver.toDate(ANCHOR, 1)).isEqualTo(ANCHOR);
        assertThat(WorkStartResolver.toDate(ANCHOR, 2)).isEqualTo(ANCHOR);
        assertThat(WorkStartResolver.toDate(ANCHOR, 3)).isEqualTo(ANCHOR.plusDays(1));
        assertThat(WorkStartResolver.toDate(ANCHOR, 4)).isEqualTo(ANCHOR.plusDays(1));
        assertThat(WorkStartResolver.toDate(ANCHOR, 21)).isEqualTo(ANCHOR.plusDays(10));
    }

    @Test
    void conversion_nullInputsGiveNull() {
        assertThat(WorkStartResolver.toDate(null, 3)).isNull();
        assertThat(WorkStartResolver.toDate(ANCHOR, null)).isNull();
    }

    @Test
    void resolve_usesEarliestBarAndKeepsOnlyRequestedPairs() {
        when(dao.findEarliestBarStarts(any(), any())).thenReturn(List.of(
                new EarliestBarRow(1L, 10L, ANCHOR, 5),
                new EarliestBarRow(1L, 11L, ANCHOR, 1),
                new EarliestBarRow(2L, 10L, ANCHOR, 1)));

        Map<ProjectCategory, LocalDate> result = resolver.resolve(List.of(
                new ProjectCategory(1L, 10L), new ProjectCategory(2L, 10L)));

        assertThat(result).containsOnly(
                Map.entry(new ProjectCategory(1L, 10L), ANCHOR.plusDays(2)),
                Map.entry(new ProjectCategory(2L, 10L), ANCHOR));
        verify(dao, times(1)).findEarliestBarStarts(any(), any());
    }

    @Test
    void resolve_noBarOrNoStartDateGivesNoEntry() {
        when(dao.findEarliestBarStarts(any(), any())).thenReturn(List.of(
                new EarliestBarRow(1L, 10L, null, 3)));

        Map<ProjectCategory, LocalDate> result = resolver.resolve(List.of(
                new ProjectCategory(1L, 10L), new ProjectCategory(1L, 12L)));

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_emptyInputSkipsQuery() {
        assertThat(resolver.resolve(List.of())).isEmpty();
        verify(dao, never()).findEarliestBarStarts(any(), any());
    }
}
