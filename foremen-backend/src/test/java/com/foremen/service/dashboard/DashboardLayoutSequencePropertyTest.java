package com.foremen.service.dashboard;

import com.foremen.dao.DashboardWidgetDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.UserDashboardWidgetDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserDashboardWidgetEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sequence properties of {@link DashboardLayoutService#movePlacement} / {@link DashboardLayoutService#swapPlacements}
 * against an in-memory DAO fake.
 * <b>Validates: Requirements 19.7, 19.8, 19.9, 19.10, 19.13, 19.14, 19.17</b>
 */
class DashboardLayoutSequencePropertyTest {

    private static final Long USER = 7L;

    /** One operation: swap or move; i/j pick placements (modulo size), cell is the move target. */
    record Op(boolean swap, int i, int j, int cell) { }

    /** Widget sizes {cs, rs}. */
    record Layout(List<int[]> sizes, List<Op> ops) { }

    /** In-memory fixture: service wired to fake DAOs holding {@link #stored}. */
    private static final class Fixture {
        final List<UserDashboardWidgetEntity> stored = new ArrayList<>();
        final AtomicInteger saves = new AtomicInteger();
        final DashboardLayoutService service;

        Fixture(List<int[]> sizes) {
            UserDashboardWidgetDao placementDao = mock(UserDashboardWidgetDao.class);
            DashboardWidgetDao widgetDao = mock(DashboardWidgetDao.class);
            UserDao userDao = mock(UserDao.class);
            ProjectAccessCache cache = mock(ProjectAccessCache.class);
            DashboardAccessService access = mock(DashboardAccessService.class);
            @SuppressWarnings("unchecked")
            ObjectProvider<WidgetResourceResolver> provider = mock(ObjectProvider.class);
            EntityManager em = mock(EntityManager.class);
            Query q = mock(Query.class);
            service = new DashboardLayoutService(placementDao, widgetDao, userDao, mock(ProjectDao.class), cache,
                    access, provider);
            ReflectionTestUtils.setField(service, "entityManager", em);
            when(em.createNativeQuery(any(String.class))).thenReturn(q);
            when(q.setParameter(any(String.class), any())).thenReturn(q);
            when(provider.getIfAvailable()).thenReturn(w -> Set.of("WORKS"));
            RoleEntity role = new RoleEntity();
            role.setCode("MANAGER");
            UserEntity user = new UserEntity();
            user.setId(USER);
            user.setRole(role);
            when(userDao.findById(USER)).thenReturn(Optional.of(user));
            when(access.missingResources(any(), any())).thenReturn(Set.of());
            when(access.evaluate(any(), any(), anyBoolean(), any(), anyBoolean(), any()))
                    .thenReturn(WidgetState.AVAILABLE);
            when(placementDao.findByUserIdOrdered(USER)).thenAnswer(i -> new ArrayList<>(stored));
            when(placementDao.findById(anyLong())).thenAnswer(i -> stored.stream()
                    .filter(p -> p.getId().equals(i.getArgument(0))).findFirst());
            when(placementDao.save(any(UserDashboardWidgetEntity.class))).thenAnswer(i -> {
                UserDashboardWidgetEntity p = i.getArgument(0);
                saves.incrementAndGet();
                if (!stored.contains(p)) {
                    p.setId(1000L + stored.size());
                    stored.add(p);
                }
                return p;
            });
            for (int k = 0; k < sizes.size(); k++) {
                long id = k + 1;
                DashboardWidgetEntity w = new DashboardWidgetEntity();
                w.setId(id);
                w.setCode("W" + id);
                w.setNameRu("ru");
                w.setNamePl("pl");
                w.setColSpan((short) sizes.get(k)[0]);
                w.setRowSpan((short) sizes.get(k)[1]);
                w.setProjectScope("NONE");
                w.setActive(true);
                w.setConfig(Map.of("visualization", "KPI"));
                when(widgetDao.findById(id)).thenReturn(Optional.of(w));
                try {
                    service.addPlacement(USER, id, null, null);
                } catch (ForemenApiException grid) {
                    // grid full: skip this widget
                }
            }
            saves.set(0);
        }

        /** placement id -> cell. */
        Map<Long, Integer> cells() {
            Map<Long, Integer> m = new HashMap<>();
            stored.forEach(p -> m.put(p.getId(), (int) p.getCellIndex()));
            return m;
        }

        /** placement id -> widgetId. */
        Map<Long, Long> widgets() {
            Map<Long, Long> m = new HashMap<>();
            stored.forEach(p -> m.put(p.getId(), p.getWidget().getId()));
            return m;
        }

        void assertNoOverlapInsideGrid() {
            long acc = 0L;
            for (UserDashboardWidgetEntity p : stored) {
                int cs = p.getWidget().getColSpan();
                int rs = p.getWidget().getRowSpan();
                assertThat(GridMath.fits(p.getCellIndex(), cs, rs)).isTrue();
                long fp = GridMath.footprint(p.getCellIndex(), cs, rs);
                assertThat(fp & acc).as("overlap at placement %d", p.getId()).isZero();
                acc |= fp;
            }
        }

        UserDashboardWidgetEntity pick(int i) {
            return stored.get(Math.floorMod(i, stored.size()));
        }
    }

    @Provide
    Arbitrary<Layout> layouts() {
        Arbitrary<int[]> size = Arbitraries.integers().between(1, 3).flatMap(c ->
                Arbitraries.integers().between(1, 3).map(r -> new int[]{c, r}));
        Arbitrary<Op> op = Arbitraries.integers().between(0, 1).flatMap(sw ->
                Arbitraries.integers().between(0, 11).flatMap(i ->
                        Arbitraries.integers().between(0, 11).flatMap(j ->
                                Arbitraries.integers().between(-2, 38).map(c -> new Op(sw == 1, i, j, c)))));
        return size.list().ofMinSize(2).ofMaxSize(8).flatMap(s ->
                op.list().ofMinSize(1).ofMaxSize(25).map(o -> new Layout(s, o)));
    }

    private static void apply(Fixture f, Op op) {
        if (f.stored.isEmpty()) {
            return;
        }
        Long a = f.pick(op.i()).getId();
        Long b = f.pick(op.j()).getId();
        if (op.swap()) {
            f.service.swapPlacements(USER, a, b);
        } else {
            f.service.movePlacement(USER, a, op.cell());
        }
    }

    /** Property 16: moves and swaps never create overlaps; a rejected request changes nothing. */
    @Property(tries = 200)
    void movesAndSwapsNeverCreateOverlaps(@ForAll("layouts") Layout layout) {
        Fixture f = new Fixture(layout.sizes());
        f.assertNoOverlapInsideGrid();
        for (Op op : layout.ops()) {
            Map<Long, Integer> before = f.cells();
            try {
                apply(f, op);
            } catch (ForemenApiException rejected) {
                assertThat(f.cells()).as("rejected request must not change anything").isEqualTo(before);
            }
            f.assertNoOverlapInsideGrid();
            if (op.swap() && !f.stored.isEmpty()) {
                Long a = f.pick(op.i()).getId();
                Long b = f.pick(op.j()).getId();
                Map<Long, Integer> after = f.cells();
                boolean aChanged = !after.get(a).equals(before.get(a));
                boolean bChanged = !after.get(b).equals(before.get(b));
                assertThat(aChanged).as("swap changes both or neither").isEqualTo(bChanged);
            }
        }
    }

    /** Property 17: a successful move followed by a move back restores the layout; same-cell move does not write. */
    @Property(tries = 200)
    void moveRoundTripAndIdempotence(@ForAll("layouts") Layout layout) {
        Fixture f = new Fixture(layout.sizes());
        if (f.stored.isEmpty()) {
            return;
        }
        for (Op op : layout.ops()) {
            UserDashboardWidgetEntity p = f.pick(op.i());
            Long id = p.getId();
            int original = p.getCellIndex();

            int savesBefore = f.saves.get();
            Map<Long, Integer> snapshot = f.cells();
            f.service.movePlacement(USER, id, original);
            assertThat(f.cells()).isEqualTo(snapshot);
            assertThat(f.saves.get()).as("same-cell move must not write").isEqualTo(savesBefore);

            try {
                f.service.movePlacement(USER, id, op.cell());
            } catch (ForemenApiException rejected) {
                assertThat(f.cells()).isEqualTo(snapshot);
                continue;
            }
            f.service.movePlacement(USER, id, original);
            assertThat(f.cells()).as("move + move back restores layout").isEqualTo(snapshot);
        }
    }

    /** Property 18: only cell_index changes; widget/project/placement identity and other cells stay. */
    @Property(tries = 200)
    void movesChangeOnlyTheCell(@ForAll("layouts") Layout layout) {
        Fixture f = new Fixture(layout.sizes());
        Map<Long, Long> widgets = f.widgets();
        int count = f.stored.size();
        for (Op op : layout.ops()) {
            Map<Long, Integer> before = f.cells();
            Set<Long> touched = new java.util.HashSet<>();
            if (!f.stored.isEmpty()) {
                touched.add(f.pick(op.i()).getId());
                if (op.swap()) {
                    touched.add(f.pick(op.j()).getId());
                }
            }
            try {
                apply(f, op);
            } catch (ForemenApiException ignored) {
                // rejected: covered by property 16
            }
            Map<Long, Integer> after = f.cells();
            before.forEach((id, cell) -> {
                if (!touched.contains(id)) {
                    assertThat(after.get(id)).as("untouched placement %d", id).isEqualTo(cell);
                }
            });
            assertThat(f.stored).hasSize(count);
            assertThat(f.widgets()).isEqualTo(widgets);
            f.stored.forEach(p -> assertThat(p.getProject()).isNull());
        }
    }
}
