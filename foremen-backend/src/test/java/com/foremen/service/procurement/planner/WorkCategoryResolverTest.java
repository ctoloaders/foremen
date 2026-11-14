package com.foremen.service.procurement.planner;

import com.foremen.service.procurement.planner.WorkCategoryResolver.Resolution;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Scheduled;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Source;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates: Requirements 5.1, 5.2, 5.3, 5.8, 5.9 */
class WorkCategoryResolverTest {

    private static final LocalDate D1 = LocalDate.of(2026, 3, 2);
    private final WorkCategoryResolver resolver = new WorkCategoryResolver();

    private static Map<Long, Scheduled> scheduled(Scheduled... items) {
        Map<Long, Scheduled> m = new LinkedHashMap<>();
        for (Scheduled s : items) {
            m.put(s.categoryId(), s);
        }
        return m;
    }

    @Test
    void manualWins_evenWhenNotScheduled() {
        Resolution r = resolver.resolve(99L, 1L, Map.of(1L, Set.of(10L)), Map.of(),
                scheduled(new Scheduled(10L, "A", D1)));
        assertThat(r.source()).isEqualTo(Source.MANUAL);
        assertThat(r.categoryId()).isEqualTo(99L);
        assertThat(r.candidates()).isEmpty();
    }

    @Test
    void tier1BeatsTier2() {
        Resolution r = resolver.resolve(null, 1L, Map.of(1L, Set.of(10L)), Map.of(1L, Set.of(11L)),
                scheduled(new Scheduled(10L, "A", D1.plusDays(5)), new Scheduled(11L, "B", D1)));
        assertThat(r.categoryId()).isEqualTo(10L);
        assertThat(r.source()).isEqualTo(Source.AUTO);
        assertThat(r.candidates()).isEmpty();
    }

    @Test
    void tier2UsedWhenTier1HasNoScheduledCategory() {
        Resolution r = resolver.resolve(null, 1L, Map.of(1L, Set.of(10L)), Map.of(1L, Set.of(11L)),
                scheduled(new Scheduled(11L, "B", D1)));
        assertThat(r.categoryId()).isEqualTo(11L);
        assertThat(r.source()).isEqualTo(Source.AUTO);
    }

    @Test
    void earliestStartWins_restAreCandidates() {
        Resolution r = resolver.resolve(null, 1L, Map.of(1L, Set.of(10L, 11L, 12L)), Map.of(),
                scheduled(new Scheduled(10L, "A", D1.plusDays(3)),
                        new Scheduled(11L, "B", D1),
                        new Scheduled(12L, "C", null)));
        assertThat(r.categoryId()).isEqualTo(11L);
        assertThat(r.candidates()).extracting(Scheduled::categoryId).containsExactly(10L, 12L);
    }

    @Test
    void tieBreak_nameThenId() {
        Resolution r = resolver.resolve(null, 1L, Map.of(1L, Set.of(10L, 11L, 12L)), Map.of(),
                scheduled(new Scheduled(12L, "A", D1), new Scheduled(11L, "A", D1),
                        new Scheduled(10L, "B", D1)));
        assertThat(r.categoryId()).isEqualTo(11L);
        assertThat(r.candidates()).extracting(Scheduled::categoryId).containsExactly(12L, 10L);
    }

    @Test
    void none_whenNoTypeOrNothingScheduled() {
        assertThat(resolver.resolve(null, null, Map.of(), Map.of(), scheduled(new Scheduled(10L, "A", D1)))
                .source()).isEqualTo(Source.NONE);
        assertThat(resolver.resolve(null, 1L, Map.of(1L, Set.of(10L)), Map.of(1L, Set.of(10L)), Map.of())
                .source()).isEqualTo(Source.NONE);
        Resolution r = resolver.resolve(null, 1L, Map.of(), Map.of(), scheduled(new Scheduled(10L, "A", D1)));
        assertThat(r.categoryId()).isNull();
        assertThat(r.candidates()).isEmpty();
    }

    @Test
    void nullMapsAreTolerated() {
        Resolution r = resolver.resolve(null, 1L, null, null, scheduled(new Scheduled(10L, "A", D1)));
        assertThat(r.source()).isEqualTo(Source.NONE);
        assertThat(List.of(r.candidates())).hasSize(1);
    }
}
