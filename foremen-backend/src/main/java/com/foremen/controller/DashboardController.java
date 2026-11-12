package com.foremen.controller;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.dashboard.DashboardDataService;
import com.foremen.service.dashboard.DashboardLayoutService;
import com.foremen.service.dashboard.DashboardLibraryService;
import com.foremen.service.dashboard.PlacementView;
import com.foremen.service.dashboard.query.WidgetResult;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * The caller's own dashboard (FOR-10 design "REST API", user part).
 *
 * <p>Deliberately <b>self-service</b>: no {@code @PermissionResource} / {@code @PermissionOperation} /
 * {@code @RequiresPermission}, so {@code PermissionAnnotationValidator} classifies it as intentionally
 * unguarded (like {@code DisplayPreferencesController}). Every operation is scoped to the authenticated
 * user id; data access is enforced inside {@link DashboardDataService} (Has_Access on the widget's
 * Required_Resources, project and row scoping). No request parameter can widen access (R6.7).
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardLayoutService layoutService;
    private final DashboardLibraryService libraryService;
    private final DashboardDataService dataService;
    private final com.foremen.service.dashboard.appearance.DashboardAppearanceService appearanceService;

    /** Layout envelope; per-item style fields are added by the appearance work (FOR-10 task 2.8). */
    public record DashboardResponse(List<PlacementView> items) {
    }

    public record AddItemRequest(Long widgetId, Integer cellIndex, Long projectId) {
    }

    @GetMapping
    public DashboardResponse layout() {
        return new DashboardResponse(appearanceService.applyStyles(layoutService.read(actingUserId())));
    }

    /** Own appearance: palette (null = follow theme) and per-placement style overrides (R5.7-5.11). */
    @GetMapping("/appearance")
    public com.foremen.service.dashboard.appearance.DashboardAppearanceService.AppearanceView appearance() {
        return appearanceService.get(actingUserId());
    }

    /**
     * Partial update: absent keys are untouched, {@code null} resets ({@code palette} or a placement style).
     */
    @org.springframework.web.bind.annotation.PutMapping("/appearance")
    @SuppressWarnings("unchecked")
    public com.foremen.service.dashboard.appearance.DashboardAppearanceService.AppearanceView updateAppearance(
            @RequestBody java.util.Map<String, Object> body) {
        boolean paletteProvided = body != null && body.containsKey("palette");
        List<String> palette = null;
        java.util.Map<Long, String> styles = null;
        try {
            if (paletteProvided && body.get("palette") != null) {
                palette = new java.util.ArrayList<>();
                for (Object c : (List<Object>) body.get("palette")) {
                    palette.add(c == null ? null : c.toString());
                }
            }
            if (body != null && body.get("styles") != null) {
                styles = new java.util.HashMap<>();
                for (java.util.Map.Entry<String, Object> e
                        : ((java.util.Map<String, Object>) body.get("styles")).entrySet()) {
                    styles.put(Long.valueOf(e.getKey()), e.getValue() == null ? null : e.getValue().toString());
                }
            }
        } catch (ClassCastException | NumberFormatException ex) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.appearance.style.invalid");
        }
        return appearanceService.update(actingUserId(), paletteProvided, palette, styles);
    }

    @GetMapping("/library")
    public List<DashboardLibraryService.LibraryItem> library() {
        return libraryService.library(actingUserId());
    }

    @PostMapping("/items")
    public ResponseEntity<PlacementView> add(@RequestBody AddItemRequest request) {
        if (request == null || request.widgetId() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.widget.notFound");
        }
        PlacementView created = layoutService.addPlacement(actingUserId(), request.widgetId(),
                request.cellIndex(), request.projectId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    public record MoveItemRequest(Integer cellIndex) {
    }

    public record SwapItemsRequest(Long firstId, Long secondId) {
    }

    public record SwapResponse(List<PlacementView> items) {
    }

    /** Moves an own placement to another cell (R19.7-19.10); null {@code cellIndex} gives 400. */
    @org.springframework.web.bind.annotation.PutMapping("/items/{id}/move")
    public PlacementView move(@PathVariable Long id, @RequestBody(required = false) MoveItemRequest request) {
        if (request == null || request.cellIndex() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.cell.invalid");
        }
        return layoutService.movePlacement(actingUserId(), id, request.cellIndex());
    }

    /** Atomically swaps the cells of two own placements (R19.13, 19.14). */
    @PostMapping("/items/swap")
    public SwapResponse swap(@RequestBody(required = false) SwapItemsRequest request) {
        if (request == null || request.firstId() == null || request.secondId() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.cell.invalid");
        }
        return new SwapResponse(layoutService.swapPlacements(actingUserId(), request.firstId(), request.secondId()));
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        layoutService.removePlacement(actingUserId(), id);
        return ResponseEntity.noContent().build();
    }

    /** {@code dateFrom}/{@code dateTo} are inclusive ISO dates; both or neither (R2.5, R2.6). */
    @GetMapping("/items/{id}/data")
    public WidgetResult data(@PathVariable Long id,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                             LocalDate dateFrom,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                             LocalDate dateTo) {
        return dataService.fetch(actingUserId(), id, dateFrom, dateTo);
    }

    @GetMapping("/projects")
    public List<DashboardLibraryService.ProjectOption> projects(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean all) {
        return libraryService.projects(actingUserId(), q, all);
    }

    /** The numeric user id from the security-context principal (same as {@code NotificationController}). */
    private Long actingUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || auth.getName().isBlank()) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        try {
            return Long.valueOf(auth.getName().trim());
        } catch (NumberFormatException ex) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
    }
}
