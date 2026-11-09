package com.foremen.service.dashboard.appearance;

import com.foremen.dao.UserDashboardAppearanceDao;
import com.foremen.dao.UserDashboardWidgetDao;
import com.foremen.dao.UserDashboardWidgetStyleDao;
import com.foremen.dao.model.UserDashboardAppearanceEntity;
import com.foremen.dao.model.UserDashboardWidgetEntity;
import com.foremen.dao.model.UserDashboardWidgetStyleEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.dashboard.PlacementView;
import com.foremen.service.dashboard.config.WidgetConfig.Visualization;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-user dashboard appearance (FOR-10 R5.7-5.11): custom palette and per-placement style overrides.
 * Always operates on the authenticated user passed by the controller.
 */
@Service
@RequiredArgsConstructor
public class DashboardAppearanceService {

    private final UserDashboardAppearanceDao appearanceDao;
    private final UserDashboardWidgetStyleDao styleDao;
    private final UserDashboardWidgetDao placementDao;
    private final PaletteValidator paletteValidator;

    /** Appearance snapshot: {@code palette} null = follow the theme; {@code styles} keyed by placement id. */
    public record AppearanceView(List<String> palette, Map<Long, String> styles) {
    }

    @Transactional(readOnly = true)
    public AppearanceView get(Long userId) {
        List<String> palette = appearanceDao.findById(userId)
                .map(UserDashboardAppearanceEntity::getPalette).orElse(null);
        Map<Long, UserDashboardWidgetEntity> placements = placementsById(userId);
        Map<Long, String> styles = new HashMap<>();
        for (Map.Entry<Long, String> e : overrides(placements.keySet()).entrySet()) {
            Visualization v = visualizationOf(placements.get(e.getKey()));
            if (WidgetStyleCatalog.isAllowed(v, e.getValue())) {
                styles.put(e.getKey(), e.getValue());
            }
        }
        return new AppearanceView(palette == null ? null : List.copyOf(palette), styles);
    }

    /**
     * Partial update. {@code paletteProvided=false} leaves the palette untouched; a provided {@code null}
     * resets it. {@code styles == null} leaves styles untouched; a {@code null} value resets that placement.
     */
    @Transactional
    public AppearanceView update(Long userId, boolean paletteProvided, List<String> palette,
                                 Map<Long, String> styles) {
        Map<Long, UserDashboardWidgetEntity> own = placementsById(userId);
        List<String> validated = paletteProvided && palette != null ? paletteValidator.validate(palette) : null;

        if (styles != null) {
            for (Map.Entry<Long, String> e : styles.entrySet()) {
                UserDashboardWidgetEntity p = own.get(e.getKey());
                if (p == null) {
                    boolean exists = e.getKey() != null && placementDao.existsById(e.getKey());
                    throw exists
                            ? new ForemenApiException(HttpStatus.FORBIDDEN, "error.dashboard.forbidden")
                            : new ForemenApiException(HttpStatus.NOT_FOUND, "error.dashboard.placement.notFound");
                }
                if (e.getValue() != null && !WidgetStyleCatalog.isAllowed(visualizationOf(p), e.getValue())) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.appearance.style.invalid");
                }
            }
        }

        if (paletteProvided) {
            UserDashboardAppearanceEntity row = appearanceDao.findById(userId).orElseGet(() -> {
                UserDashboardAppearanceEntity r = new UserDashboardAppearanceEntity();
                r.setUserId(userId);
                return r;
            });
            row.setPalette(validated);
            row.setUpdatedAt(LocalDateTime.now());
            appearanceDao.save(row);
        }

        // clean stale overrides (no longer in the catalog) and apply the requested changes
        Map<Long, String> current = overrides(own.keySet());
        for (Map.Entry<Long, String> e : current.entrySet()) {
            if (!WidgetStyleCatalog.isAllowed(visualizationOf(own.get(e.getKey())), e.getValue())) {
                styleDao.deleteById(e.getKey());
            }
        }
        if (styles != null) {
            for (Map.Entry<Long, String> e : styles.entrySet()) {
                if (e.getValue() == null) {
                    if (current.containsKey(e.getKey())) {
                        styleDao.deleteById(e.getKey());
                    }
                } else {
                    UserDashboardWidgetStyleEntity s = new UserDashboardWidgetStyleEntity();
                    s.setPlacementId(e.getKey());
                    s.setStyle(e.getValue());
                    styleDao.save(s);
                }
            }
        }
        return get(userId);
    }

    /** Returns the views with the caller's style overrides applied as the effective {@code style}. */
    @Transactional(readOnly = true)
    public List<PlacementView> applyStyles(List<PlacementView> views) {
        if (views.isEmpty()) {
            return views;
        }
        Map<Long, String> overrides = overrides(views.stream().map(PlacementView::id).collect(Collectors.toSet()));
        List<PlacementView> out = new ArrayList<>(views.size());
        for (PlacementView v : views) {
            Visualization viz = parse(v.visualization());
            out.add(v.withStyle(effectiveStyle(viz, v.defaultStyle(), overrides.get(v.id()))));
        }
        return out;
    }

    /**
     * Effective style: user override (if still in the catalog) → config/default style (if in the catalog)
     * → first catalog entry. Pure; null when the visualization is unknown.
     */
    public static String effectiveStyle(Visualization viz, String configStyle, String override) {
        if (viz == null) {
            return null;
        }
        if (WidgetStyleCatalog.isAllowed(viz, override)) {
            return override;
        }
        if (WidgetStyleCatalog.isAllowed(viz, configStyle)) {
            return configStyle;
        }
        return WidgetStyleCatalog.defaultStyle(viz);
    }

    public static Visualization parse(String visualization) {
        if (visualization == null) {
            return null;
        }
        try {
            return Visualization.valueOf(visualization);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Visualization visualizationOf(UserDashboardWidgetEntity p) {
        Map<String, Object> cfg = p.getWidget().getConfig();
        Object v = cfg == null ? null : cfg.get("visualization");
        return v == null ? null : parse(v.toString());
    }

    private Map<Long, UserDashboardWidgetEntity> placementsById(Long userId) {
        Map<Long, UserDashboardWidgetEntity> m = new HashMap<>();
        for (UserDashboardWidgetEntity p : placementDao.findByUserIdOrdered(userId)) {
            m.put(p.getId(), p);
        }
        return m;
    }

    private Map<Long, String> overrides(Set<Long> placementIds) {
        Map<Long, String> m = new HashMap<>();
        if (placementIds.isEmpty()) {
            return m;
        }
        for (UserDashboardWidgetStyleEntity s : styleDao.findByPlacementIdIn(placementIds)) {
            m.put(s.getPlacementId(), s.getStyle());
        }
        return m;
    }
}
