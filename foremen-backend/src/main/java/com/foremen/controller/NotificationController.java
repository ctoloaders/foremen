package com.foremen.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.offer.NotificationService;
import com.foremen.service.offer.NotificationView;

import lombok.RequiredArgsConstructor;

/**
 * The in-app notification bell controller (FOR-05-07, design §NotificationService) — the acting
 * user's own-notification operations, layered on the new {@code NOTIFICATIONS} ABAC resource (seeded
 * by changeset 134, which grants READ/UPDATE/DELETE to <b>every</b> role and NO CREATE to end
 * users).
 *
 * <h2>ABAC guarding (R13.13)</h2>
 * The class carries {@link PermissionResource @PermissionResource("NOTIFICATIONS")}; every handler
 * carries a method-level {@link RequiresPermission @RequiresPermission} that takes precedence over the
 * class default (mirroring {@link EstimateMatrixController}), so the controller is <b>fully
 * annotated</b> and {@code PermissionAnnotationValidator} classifies it COMPLETE at startup:
 * <ul>
 *   <li>{@code GET /}, {@code GET /unread-count} — the acting user's notifications / unread badge
 *       count, {@code NOTIFICATIONS} READ (R13.4, R10.10).</li>
 *   <li>{@code POST /{notificationId}/toggle-read} — flip the read flag of an own notification,
 *       {@code NOTIFICATIONS} UPDATE (R13.6).</li>
 *   <li>{@code DELETE /{notificationId}} — delete an own notification, {@code NOTIFICATIONS} DELETE
 *       (R13.7).</li>
 * </ul>
 * There is deliberately <b>no CREATE endpoint</b>: notifications are created only by server-side
 * flows through {@code NotificationService.create}, never by an end-user grant (R13.13).
 *
 * <h2>Ownership</h2>
 * Every operation is scoped to the acting user resolved from the security-context principal (the
 * numeric user id, matching {@code ProjectScopedService}); {@link NotificationService} enforces that
 * a toggle/delete only ever touches the caller's own notification ({@code 403
 * error.notification.forbidden}) and that reads return only the caller's own rows (R13.8/R13.9).
 * Ownership is enforced by the acting user id, never by project scoping (R10.9/R10.19).
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@PermissionResource("NOTIFICATIONS")
public class NotificationController {

    private static final String NOTIFICATIONS_RESOURCE = "NOTIFICATIONS";
    private static final String READ_OPERATION = "READ";
    private static final String UPDATE_OPERATION = "UPDATE";
    private static final String DELETE_OPERATION = "DELETE";

    private final NotificationService notificationService;

    /**
     * The acting user's own notifications, newest first (R13.4/R13.8). {@code NOTIFICATIONS} READ.
     */
    @GetMapping
    @RequiresPermission(resource = NOTIFICATIONS_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<List<NotificationView>> list() {
        List<NotificationView> views = notificationService.listForActingUser(actingUserId()).stream()
                .map(NotificationView::of)
                .toList();
        return ResponseEntity.ok(views);
    }

    /**
     * The bell unread-badge count — the number of the acting user's unread notifications (R10.10).
     * {@code NOTIFICATIONS} READ.
     */
    @GetMapping("/unread-count")
    @RequiresPermission(resource = NOTIFICATIONS_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<UnreadCountResponse> unreadCount() {
        return ResponseEntity.ok(new UnreadCountResponse(notificationService.unreadCount(actingUserId())));
    }

    /**
     * Toggles the {@code read} flag of the acting user's own notification (R13.6). A foreign
     * notification is rejected {@code 403 error.notification.forbidden}. {@code NOTIFICATIONS} UPDATE.
     */
    @PostMapping("/{notificationId}/toggle-read")
    @RequiresPermission(resource = NOTIFICATIONS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<NotificationView> toggleRead(@PathVariable Long notificationId) {
        return ResponseEntity.ok(
                NotificationView.of(notificationService.toggleRead(actingUserId(), notificationId)));
    }

    /**
     * Deletes the acting user's own notification (R13.7). A foreign notification is rejected
     * {@code 403 error.notification.forbidden}. {@code NOTIFICATIONS} DELETE.
     */
    @DeleteMapping("/{notificationId}")
    @RequiresPermission(resource = NOTIFICATIONS_RESOURCE, operation = DELETE_OPERATION)
    public ResponseEntity<Void> delete(@PathVariable Long notificationId) {
        notificationService.delete(actingUserId(), notificationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The acting user's numeric id, read from the security-context principal name (the numeric user
     * id, matching {@code ProjectScopedService}). A missing / non-numeric principal is rejected
     * {@code 401 error.auth.unauthorized} — defensive, since reaching a guarded handler already proves
     * an authenticated principal.
     */
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

    /** Response body for {@code GET /unread-count}: the acting user's unread notification count. */
    public record UnreadCountResponse(long unreadCount) {
    }
}
