package com.foremen.service;

import com.foremen.controller.model.WorkerInvitationResponse;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code Worker_Invitation_Flow} (FOR-05-09, Requirements 13.17&ndash;13.18, D7, D-new):
 * sends / re-sends the FOR-03-02 <strong>staff</strong> password-set email to a WORKER user who was
 * created without an invitation ({@code Worker_Record_Flow}, task 14.2) so the worker sets their own
 * password and reaches the "active" lifecycle state.
 *
 * <p>Exposed as {@code POST /api/users/worker/{id}/invite}. Mirrors (does not generalize) the
 * client-registration flow: a single {@code @Transactional} method with the role fixed server-side.
 *
 * <p><b>Dual-permission guard (D-new, Requirement 13.18 last sentence).</b> The flow requires both
 * {@code PROJECT_MEMBERS} CREATE and {@code PROJECTS} EDIT. The {@code PROJECTS/EDIT} half is
 * declared on the controller handler with {@code @RequiresPermission} (the FOR-03-05 pattern,
 * whitelisted for {@code UserController}); the {@code PROJECT_MEMBERS/CREATE} half is asserted here
 * programmatically via {@link ForemenPermissionEvaluator}, with the ADMIN bypass. A caller lacking
 * either operation receives 403 {@code error.access.denied}.
 *
 * <p><b>Canonical rejection order (Requirement 13.18).</b> The first tripped check wins and no email
 * is sent and no user state is changed on any rejection:
 * <ol>
 *   <li>permission &mdash; 403 {@code error.access.denied} ({@code PROJECT_MEMBERS} CREATE here, the
 *       {@code PROJECTS} EDIT half upstream);</li>
 *   <li>user existence &mdash; 404 {@code error.entity.not.found} for a non-existent user;</li>
 *   <li>WORKER role &mdash; 400 {@code error.worker.invite.not.allowed} for a non-WORKER user;</li>
 *   <li>activation state &mdash; 409 {@code error.worker.already.active} for a user that has already
 *       set a password;</li>
 *   <li>stored email &mdash; 400 {@code error.worker.email.required} for a WORKER with no email.</li>
 * </ol>
 * Only when all pass does the flow invite / re-invite the worker and respond 200.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WorkerInvitationService {

    private static final String WORKER_ROLE_CODE = "WORKER";
    private static final String PROJECT_MEMBERS_RESOURCE = "PROJECT_MEMBERS";
    private static final String CREATE_OPERATION = "CREATE";
    private static final String ROLE_PREFIX = "ROLE_";

    private final UserDao userDao;
    private final InviteService inviteService;
    private final ForemenPermissionEvaluator permissionEvaluator;

    /**
     * Sends / re-sends the staff password-set email to a not-yet-activated WORKER user with a stored
     * email, issuing a fresh link that supersedes any earlier unused one and setting the user's
     * status to reflect "invited" (Requirement 13.17). Enforces the Requirement 13.18 rejection
     * order; every rejection sends no email and changes no user state (the checks throw before
     * {@link InviteService#inviteWorker(UserEntity)} runs, and this method is {@code @Transactional}).
     *
     * @param userId the id of the WORKER user to invite / re-invite
     * @return the 200 response payload echoing the invited user's id, email, and status
     * @throws ForemenApiException per the canonical order documented on the class
     */
    public WorkerInvitationResponse invite(Long userId) {
        // Step 1 (permission) — the PROJECT_MEMBERS CREATE half of the dual guard (the PROJECTS EDIT
        // half is enforced on the controller handler). ADMIN bypasses via the evaluator (Req 13.18).
        assertProjectMembersCreate();

        // Step 2 (user existence) — 404 error.entity.not.found, indistinguishable from any other
        // missing entity (Req 13.18). No email, no state change.
        UserEntity user = userDao.findById(userId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, "error.entity.not.found", userId));

        // Step 3 (WORKER role) — only a WORKER user may be invited through this flow (Req 13.18).
        RoleEntity role = user.getRole();
        String roleCode = role == null ? null : role.getCode();
        if (!WORKER_ROLE_CODE.equals(roleCode)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.worker.invite.not.allowed");
        }

        // Step 4 (activation state) — a worker that has already set a password (reached "active") can
        // no longer be invited (Req 13.18). "Activated" is a set password or an ACTIVE status.
        if (isActivated(user)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.worker.already.active");
        }

        // Step 5 (stored email) — a worker record may have been created without an email (email is
        // optional for an uninvited worker, D8); an invite requires one (Req 13.18 first sentence).
        if (!hasStoredEmail(user)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.worker.email.required");
        }

        // All checks passed — send / re-send the staff password-set email, mint a fresh link that
        // invalidates any earlier unused one, and mark the user "invited" (Req 13.17).
        inviteService.inviteWorker(user);

        return new WorkerInvitationResponse(user.getId(), user.getEmail(), user.getStatus().name());
    }

    /**
     * Asserts the authenticated caller holds {@code PROJECT_MEMBERS} CREATE (ADMIN bypassed by the
     * evaluator), throwing 403 {@code error.access.denied} otherwise. An unresolved role is treated
     * as a denial (defense in depth; the controller's {@code @RequiresPermission(PROJECTS, EDIT)}
     * already fails an unauthenticated caller upstream).
     */
    private void assertProjectMembersCreate() {
        String roleCode = currentRoleCode();
        if (roleCode == null
                || !permissionEvaluator.isAllowed(roleCode, PROJECT_MEMBERS_RESOURCE, CREATE_OPERATION)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }
    }

    /** A worker is "activated" once it has set a password or reached the ACTIVE status (D-new). */
    private static boolean isActivated(UserEntity user) {
        String passwordHash = user.getPasswordHash();
        boolean hasPassword = passwordHash != null && !passwordHash.isBlank();
        return hasPassword || user.getStatus() == UserStatus.ACTIVE;
    }

    /** A worker has a usable stored email when it is present and non-blank after trimming. */
    private static boolean hasStoredEmail(UserEntity user) {
        String email = user.getEmail();
        return email != null && !email.isBlank();
    }

    /** The caller's role code from the {@code ROLE_<code>} authority, or {@code null} if unresolved. */
    private static String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith(ROLE_PREFIX)) {
                return authority.substring(ROLE_PREFIX.length());
            }
        }
        return null;
    }
}
