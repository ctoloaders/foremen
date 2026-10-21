package com.foremen.controller;

import java.util.List;
import java.util.Locale;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.clientmessage.ClientMessageDto;
import com.foremen.service.clientmessage.ClientMessageService;
import com.foremen.service.clientmessage.PostMessageRequest;
import com.foremen.service.clientmessage.ThreadView;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import lombok.RequiredArgsConstructor;

/**
 * FOR-06-12 (Requirements 8.3, 9.*; design §ClientMessageController) — REST controller for the
 * per-project client↔staff conversation, exposed under {@code /api/client-messages} and backed by
 * {@link ClientMessageService}.
 *
 * <p><b>ABAC (entity-creation-rules step 3).</b> The class carries
 * {@link PermissionResource @PermissionResource("CLIENT_MESSAGES")}; because this is a bespoke
 * (non-{@code AdminController}) controller, <em>every</em> handler also carries a method-level
 * {@link RequiresPermission @RequiresPermission} (mirroring {@link NotificationController} /
 * {@link EstimateMatrixController}), so {@code PermissionAnnotationValidator} classifies the
 * controller COMPLETE at startup (no half-annotation that would fail boot). The
 * {@code CLIENT_MESSAGES} resource and its grants are seeded by changeset 186 (task 8.1).
 *
 * <p><b>Endpoints.</b> Base path {@code /api/client-messages}:
 * <ul>
 *   <li>{@code GET ?projectId=} → {@code CLIENT_MESSAGES}/READ → {@link #list(Long)}: the ordered
 *       thread projected for the caller's reader locale, with the per-reader unread count (R5.4,
 *       R6.4). The project-scope gate is applied by the service.</li>
 *   <li>{@code POST} (consumes {@code multipart/form-data}) → {@code CLIENT_MESSAGES}/CREATE →
 *       {@link #post(Long, PostMessageRequest, List)}: posts a message (text JSON part + optional
 *       media files). {@code authorSide} is derived server-side from the author's project role,
 *       never from the body (R2.1); the MANAGER-only reply rule is enforced in the service
 *       (R3).</li>
 *   <li>{@code POST /{id}/read} → {@code CLIENT_MESSAGES}/UPDATE → {@link #markRead(Long)}: MANAGER
 *       first-read-wins marking of a {@code CLIENT}-side message (R5, enforced in the service).</li>
 * </ul>
 *
 * <p><b>Reader locale (design §Reader/author locale).</b> On the read the reader locale is the
 * caller's {@code UserEntity.getLocale()} — resolved here from the authenticated principal and
 * passed to {@link ClientMessageService#listThread(Long, Locale)}, which applies the shared PL
 * fallback for a {@code null}/unsupported value. The {@code post} / {@code markRead} projections
 * resolve the caller's own locale inside the service, so no locale is threaded through those.
 */
@RestController
@RequestMapping("/api/client-messages")
@RequiredArgsConstructor
@PermissionResource("CLIENT_MESSAGES")
public class ClientMessageController {

    private static final String RESOURCE = "CLIENT_MESSAGES";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";

    private final ClientMessageService clientMessageService;
    private final UserDao userDao;

    /**
     * The project's whole thread projected for the caller's reader locale, with the per-reader
     * unread count (R5.4, R6.4). {@code CLIENT_MESSAGES}/READ; the service enforces the project-scope
     * gate before reading.
     *
     * @param projectId the owning project id
     * @return the ordered, reader-localized {@link ThreadView}
     */
    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<ThreadView> list(@RequestParam Long projectId) {
        return ResponseEntity.ok(clientMessageService.listThread(projectId, callerLocale()));
    }

    /**
     * Posts a message into the project's thread (R2, R3, R4, R6). {@code CLIENT_MESSAGES}/CREATE;
     * consumes {@code multipart/form-data}: the {@code body} JSON part (text + optional explicit
     * locale) and the optional {@code media} file parts. {@code authorSide} is derived server-side
     * from the author's project role, never from the body (R2.1).
     *
     * @param projectId the owning project id
     * @param body      the post payload (text + optional explicit original locale)
     * @param media     the optional uploaded media files (may be absent/empty)
     * @return the persisted message projected for the author's own locale
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<ClientMessageDto> post(
            @RequestParam Long projectId,
            @RequestPart("body") PostMessageRequest body,
            @RequestPart(name = "media", required = false) List<MultipartFile> media) {
        return ResponseEntity.ok(clientMessageService.post(projectId, body, media));
    }

    /**
     * Marks a {@code CLIENT}-side message read on the MANAGER's first open, first-read-wins (R5).
     * {@code CLIENT_MESSAGES}/UPDATE; the MANAGER-only rule and first-read-wins semantics are
     * enforced in the service on top of the ABAC UPDATE grant.
     *
     * @param id the message to mark read
     * @return the (possibly updated) message projected for the caller's own locale
     */
    @PostMapping("/{id}/read")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<ClientMessageDto> markRead(@PathVariable Long id) {
        return ResponseEntity.ok(clientMessageService.markRead(id));
    }

    /**
     * The caller's reader locale — their {@code UserEntity.getLocale()} resolved from the
     * authenticated principal (the numeric user id, matching {@code ProjectScopedService}). A
     * missing / non-numeric principal or an unresolved user yields {@code null}, which
     * {@link ClientMessageService#listThread(Long, Locale)} maps to the shared PL fallback.
     *
     * @return the caller's preferred locale, or {@code null} when it cannot be resolved
     */
    private Locale callerLocale() {
        Long callerId = callerUserId();
        if (callerId == null) {
            return null;
        }
        return userDao.findById(callerId).map(UserEntity::getLocale).orElse(null);
    }

    /** The authenticated caller's numeric user id, or {@code null} when it cannot be resolved. */
    private static Long callerUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || auth.getName().isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(auth.getName().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
