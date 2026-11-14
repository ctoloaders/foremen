package com.foremen.service.procurement.planner;

import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.ProjectScheduleDao;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Scheduled;
import com.foremen.service.procurement.planner.WorkStartResolver.ProjectCategory;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Validates: Requirements 5.2, 5.8, 5.9 */
class ScheduledCategoryServiceTest {

    private static final Long PROJECT = 7L;
    private static final LocalDate START = LocalDate.of(2026, 3, 2);

    private final EstimateLineDao lineDao = mock(EstimateLineDao.class);
    private final ProjectScheduleDao scheduleDao = mock(ProjectScheduleDao.class);
    private final WorkStartResolver startResolver = mock(WorkStartResolver.class);
    private final ScheduledCategoryService service =
            new ScheduledCategoryService(lineDao, scheduleDao, startResolver);

    private static WorkCategoryEntity category(long id, int order) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setId(id);
        c.setOrderNo(order);
        c.setNameRU("ru" + id);
        c.setNamePL("pl" + id);
        return c;
    }

    private static EstimateLineEntity line(long id, WorkCategoryEntity category) {
        WorkItemEntity wi = new WorkItemEntity();
        wi.setId(id);
        wi.setWorkCategory(category);
        EstimateLineEntity l = new EstimateLineEntity();
        l.setId(id);
        l.setWorkItem(wi);
        return l;
    }

    private static ProjectScheduleBarEntity bar(WorkCategoryEntity c) {
        ProjectScheduleBarEntity b = new ProjectScheduleBarEntity();
        b.setWorkCategory(c);
        return b;
    }

    @Test
    void ignoresOrphanBarsAndCategoriesWithoutBars_andResolvesStart() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("pl"));
        WorkCategoryEntity a = category(1, 1);
        WorkCategoryEntity b = category(2, 2);
        WorkCategoryEntity orphan = category(3, 3);
        when(lineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT))
                .thenReturn(List.of(line(10, a), line(11, b)));
        ProjectScheduleEntity schedule = new ProjectScheduleEntity();
        // two parallel bars of category A (per worker) plus an orphan bar; B has no bar
        schedule.getBars().addAll(List.of(bar(a), bar(a), bar(orphan)));
        when(scheduleDao.findByProjectId(PROJECT)).thenReturn(Optional.of(schedule));
        when(startResolver.resolve(any())).thenReturn(Map.of(new ProjectCategory(PROJECT, 1L), START));

        Map<Long, Scheduled> result = service.scheduled(PROJECT);

        assertThat(result).containsOnlyKeys(1L);
        assertThat(result.get(1L)).isEqualTo(new Scheduled(1L, "pl1", START));
    }

    @Test
    void russianLocaleUsesRussianName() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        WorkCategoryEntity a = category(1, 1);
        when(lineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT)).thenReturn(List.of(line(10, a)));
        ProjectScheduleEntity schedule = new ProjectScheduleEntity();
        schedule.getBars().add(bar(a));
        when(scheduleDao.findByProjectId(PROJECT)).thenReturn(Optional.of(schedule));
        when(startResolver.resolve(any())).thenReturn(Map.of());

        Map<Long, Scheduled> result = service.scheduled(PROJECT);

        assertThat(result.get(1L).name()).isEqualTo("ru1");
        assertThat(result.get(1L).startDate()).isNull();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void emptyWhenNoScheduleNoEstimateOrNullProject() {
        when(lineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT)).thenReturn(List.of(line(10, category(1, 1))));
        when(scheduleDao.findByProjectId(PROJECT)).thenReturn(Optional.empty());
        assertThat(service.scheduled(PROJECT)).isEmpty();

        when(lineDao.findByProjectIdWithWorkItemCategoryAndUnit(8L)).thenReturn(List.of());
        assertThat(service.scheduled(8L)).isEmpty();
        assertThat(service.scheduled((Long) null)).isEmpty();
        verify(startResolver, never()).resolve(any());
        assertThat(Set.copyOf(service.scheduled(List.of(PROJECT, 8L)).keySet())).containsExactlyInAnyOrder(PROJECT, 8L);
    }
}
