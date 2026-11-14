package com.foremen.service.procurement.planner;

import com.foremen.service.procurement.planner.WorkCategoryResolver.Resolution;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Scheduled;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Source;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** **Validates: Requirements 5.1, 5.2, 5.3, 5.8** */
class WorkCategoryResolverPropertyTest {

    private static final Long TYPE = 1L;
    private final WorkCategoryResolver resolver = new WorkCategoryResolver();

    /** Scenario: scheduled categories 1..8 and tier sets drawn from 1..10 (so some are unscheduled). */
    record Scenario(Long manual, Set<Long> tier1, Set<Long> tier2, Map<Long, Scheduled> scheduled) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Set<Long>> ids = Arbitraries.longs().between(1, 10).set().ofMaxSize(6);
        Arbitrary<Map<Long, Scheduled>> sched = Arbitraries.longs().between(1, 8).set().ofMaxSize(8)
                .flatMap(set -> {
                    List<Long> list = new ArrayList<>(set);
                    return Arbitraries.integers().between(-1, 30).list().ofSize(list.size()).map(days -> {
                        Map<Long, Scheduled> m = new HashMap<>();
                        for (int i = 0; i < list.size(); i++) {
                            LocalDate d = days.get(i) < 0 ? null : LocalDate.of(2026, 1, 1).plusDays(days.get(i));
                            m.put(list.get(i), new Scheduled(list.get(i), "N" + (list.get(i) % 3), d));
                        }
                        return m;
                    });
                });
        Arbitrary<Long> manual = Arbitraries.longs().between(1, 10).injectNull(0.3);
        return Combinators(manual, ids, ids, sched);
    }

    private static Arbitrary<Scenario> Combinators(Arbitrary<Long> m, Arbitrary<Set<Long>> t1,
                                                    Arbitrary<Set<Long>> t2, Arbitrary<Map<Long, Scheduled>> s) {
        return net.jqwik.api.Combinators.combine(m, t1, t2, s).as(Scenario::new);
    }

    private Resolution run(Scenario s) {
        return resolver.resolve(s.manual(), TYPE, Map.of(TYPE, s.tier1()), Map.of(TYPE, s.tier2()), s.scheduled());
    }

    @Property
    void manualAlwaysWins(@ForAll("scenarios") Scenario s) {
        Resolution r = run(s);
        if (s.manual() != null) {
            assertThat(r.source()).isEqualTo(Source.MANUAL);
            assertThat(r.categoryId()).isEqualTo(s.manual());
            assertThat(r.candidates()).isEmpty();
        }
    }

    @Property
    void autoResultComesFromFirstNonEmptyTierIntersectionOnly(@ForAll("scenarios") Scenario s) {
        Scenario auto = new Scenario(null, s.tier1(), s.tier2(), s.scheduled());
        Resolution r = run(auto);

        Set<Long> t1 = new HashSet<>(s.tier1());
        t1.retainAll(s.scheduled().keySet());
        Set<Long> t2 = new HashSet<>(s.tier2());
        t2.retainAll(s.scheduled().keySet());
        Set<Long> expected = !t1.isEmpty() ? t1 : t2;

        if (expected.isEmpty()) {
            assertThat(r.source()).isEqualTo(Source.NONE);
            assertThat(r.categoryId()).isNull();
            return;
        }
        assertThat(r.source()).isEqualTo(Source.AUTO);
        Set<Long> all = new HashSet<>();
        all.add(r.categoryId());
        r.candidates().forEach(c -> all.add(c.categoryId()));
        assertThat(all).isEqualTo(expected);
        assertThat(r.candidates()).extracting(Scheduled::categoryId).doesNotContain(r.categoryId());

        // winner has the earliest start (nulls last)
        Scheduled winner = s.scheduled().get(r.categoryId());
        for (Scheduled c : r.candidates()) {
            if (winner.startDate() == null) {
                assertThat(c.startDate()).isNull();
            } else if (c.startDate() != null) {
                assertThat(c.startDate()).isAfterOrEqualTo(winner.startDate());
            }
        }
    }

    @Property
    void resolutionIsDeterministic(@ForAll("scenarios") Scenario s) {
        assertThat(run(s)).isEqualTo(run(s));
    }
}
