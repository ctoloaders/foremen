package com.foremen.service.dashboard.appearance;

import com.foremen.dao.UserDashboardAppearanceDao;
import com.foremen.dao.UserDashboardWidgetDao;
import com.foremen.dao.UserDashboardWidgetStyleDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.UserDashboardAppearanceEntity;
import com.foremen.dao.model.UserDashboardWidgetEntity;
import com.foremen.dao.model.UserDashboardWidgetStyleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.dashboard.config.WidgetConfig.Visualization;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Properties of the appearance feature.
 * <b>Validates: Requirements 5.7, 5.8, 5.9, 5.10, 5.11</b>
 */
class DashboardAppearancePropertyTest {

    // ---- style fallback ------------------------------------------------------------------------

    @Provide
    Arbitrary<String> maybeStyle() {
        return Arbitraries.of("SOLID", "STANDARD", "PLAIN", "CARD", "COMPACT", "DETAILED", "BOGUS", "")
                .injectNull(0.2);
    }

    /** Effective style is always in the catalog and follows override → config → first entry. */
    @Property
    void effectiveStyleFallsBack(@ForAll Visualization viz, @ForAll("maybeStyle") String cfg,
                                 @ForAll("maybeStyle") String override) {
        String result = DashboardAppearanceService.effectiveStyle(viz, cfg, override);
        assertThat(WidgetStyleCatalog.stylesFor(viz)).contains(result);
        if (WidgetStyleCatalog.isAllowed(viz, override)) {
            assertThat(result).isEqualTo(override);
        } else if (WidgetStyleCatalog.isAllowed(viz, cfg)) {
            assertThat(result).isEqualTo(cfg);
        } else {
            assertThat(result).isEqualTo(WidgetStyleCatalog.defaultStyle(viz));
        }
    }

    // ---- palette validation --------------------------------------------------------------------

    @Provide
    Arbitrary<String> hex() {
        return Arbitraries.strings().withChars("0123456789abcdefABCDEF").ofLength(6).map(s -> "#" + s);
    }

    @Property
    void validPalettesAccepted(@ForAll("hex") String a, @ForAll("hex") String b) {
        List<String> p = a.equalsIgnoreCase(b) ? List.of(a) : List.of(a, b);
        assertThat(new PaletteValidator().validate(p)).hasSameSizeAs(p)
                .allMatch(c -> c.equals(c.toUpperCase()));
    }

    @Property
    void invalidPalettesRejected(@ForAll("badPalette") List<String> p) {
        assertThat(PaletteValidator.isValid(p)).isFalse();
        assertThatThrownBy(() -> new PaletteValidator().validate(p)).isInstanceOfSatisfying(
                ForemenApiException.class,
                e -> assertThat(e.getMessageCode()).isEqualTo("error.dashboard.appearance.palette.invalid"));
    }

    @Provide
    Arbitrary<List<String>> badPalette() {
        Arbitrary<List<String>> bad = Arbitraries.of("red", "#FFF", "#GGGGGG", "123456", "#1234567", "")
                .list().ofMinSize(1).ofMaxSize(3);
        Arbitrary<List<String>> tooMany = Arbitraries.integers().between(9, 12).map(n -> {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                l.add(String.format("#%06X", i + 1));
            }
            return l;
        });
        Arbitrary<List<String>> dup = Arbitraries.just(List.of("#abcdef", "#ABCDEF"));
        return Arbitraries.oneOf(bad, tooMany, dup, Arbitraries.just(List.<String>of()));
    }

    // ---- isolation -----------------------------------------------------------------------------

    @Property(tries = 30)
    void updateNeverTouchesForeignPlacements(@ForAll("hex") String color) {
        UserDashboardAppearanceDao appDao = mock(UserDashboardAppearanceDao.class);
        UserDashboardWidgetStyleDao styleDao = mock(UserDashboardWidgetStyleDao.class);
        UserDashboardWidgetDao placementDao = mock(UserDashboardWidgetDao.class);
        UserDashboardWidgetEntity mine = placement(1L, 10L, "BAR");
        when(placementDao.findByUserIdOrdered(10L)).thenReturn(List.of(mine));
        when(placementDao.existsById(2L)).thenReturn(true);
        when(placementDao.existsById(99L)).thenReturn(false);
        when(styleDao.findByPlacementIdIn(any())).thenReturn(List.of());
        DashboardAppearanceService svc = new DashboardAppearanceService(appDao, styleDao, placementDao,
                new PaletteValidator());

        // foreign placement → 403, nothing written
        assertThatThrownBy(() -> svc.update(10L, true, List.of(color), Map.of(2L, "SOLID")))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(403));
        // unknown placement → 404
        assertThatThrownBy(() -> svc.update(10L, false, null, Map.of(99L, "SOLID")))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(404));
        // style outside catalog → 400
        assertThatThrownBy(() -> svc.update(10L, false, null, Map.of(1L, "PLAIN")))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(400));
        org.mockito.Mockito.verify(appDao, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(styleDao, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void partialUpdateAndNullReset() {
        UserDashboardAppearanceDao appDao = mock(UserDashboardAppearanceDao.class);
        UserDashboardWidgetStyleDao styleDao = mock(UserDashboardWidgetStyleDao.class);
        UserDashboardWidgetDao placementDao = mock(UserDashboardWidgetDao.class);
        when(placementDao.findByUserIdOrdered(10L)).thenReturn(List.of(placement(1L, 10L, "BAR")));
        Map<Long, String> db = new HashMap<>();
        UserDashboardAppearanceEntity[] row = new UserDashboardAppearanceEntity[1];
        when(appDao.findById(10L)).thenAnswer(i -> Optional.ofNullable(row[0]));
        when(appDao.save(any())).thenAnswer(i -> row[0] = i.getArgument(0));
        when(styleDao.findByPlacementIdIn(any())).thenAnswer(i -> db.entrySet().stream().map(e -> {
            UserDashboardWidgetStyleEntity s = new UserDashboardWidgetStyleEntity();
            s.setPlacementId(e.getKey());
            s.setStyle(e.getValue());
            return s;
        }).collect(Collectors.toList()));
        when(styleDao.save(any())).thenAnswer(i -> {
            UserDashboardWidgetStyleEntity s = i.getArgument(0);
            db.put(s.getPlacementId(), s.getStyle());
            return s;
        });
        org.mockito.Mockito.doAnswer(i -> db.remove(i.getArgument(0))).when(styleDao).deleteById(any());
        DashboardAppearanceService svc = new DashboardAppearanceService(appDao, styleDao, placementDao,
                new PaletteValidator());

        var v = svc.update(10L, true, List.of("#aabbcc"), Map.of(1L, "OUTLINED"));
        assertThat(v.palette()).containsExactly("#AABBCC");
        assertThat(v.styles()).containsEntry(1L, "OUTLINED");

        // styles-only update keeps the palette
        v = svc.update(10L, false, null, Map.of(1L, "MINIMAL"));
        assertThat(v.palette()).containsExactly("#AABBCC");
        assertThat(v.styles()).containsEntry(1L, "MINIMAL");

        // null resets
        java.util.HashMap<Long, String> reset = new java.util.HashMap<>();
        reset.put(1L, null);
        v = svc.update(10L, true, null, reset);
        assertThat(v.palette()).isNull();
        assertThat(v.styles()).isEmpty();
    }

    private static UserDashboardWidgetEntity placement(long id, long userId, String viz) {
        UserEntity u = new UserEntity();
        u.setId(userId);
        DashboardWidgetEntity w = new DashboardWidgetEntity();
        w.setConfig(Map.of("visualization", viz));
        UserDashboardWidgetEntity p = new UserDashboardWidgetEntity();
        p.setId(id);
        p.setUser(u);
        p.setWidget(w);
        return p;
    }
}
