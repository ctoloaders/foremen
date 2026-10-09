package com.foremen.service;

import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkerKind;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.UserServiceModel;
import com.foremen.service.model.mapper.UserServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Getter
@Transactional
public class UserService implements AdminService<
        UserServiceModel, UserServiceExtendedModel, UserEntity, Long> {

    private static final Set<String> SUPPORTED_LOCALES = Set.of("ru", "pl");
    private static final String ADMIN_ROLE_CODE = "ADMIN";
    private static final String CLIENT_ROLE_CODE = "CLIENT";

    /**
     * Per-thread flag that signals the current {@code create(...)} call originated from the
     * dedicated client-registration path ({@link #createClient}). When set, {@link #validateCreate}
     * permits the server-resolved CLIENT role; otherwise the generic {@code POST /api/users} create
     * rejects a CLIENT role as defense in depth for FOR-03-05 Requirement 10.11.
     */
    private static final ThreadLocal<Boolean> CLIENT_CREATE_ALLOWED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final UserDao dao;
    private final RoleDao roleDao;
    private final UserServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final InviteService inviteService;
    private final ProjectAccessCache projectAccessCache;
    private final Class<UserEntity> daoModelClass = UserEntity.class;

    // --- Validation hooks (framework reuses default create()/update() for audit/snapshot) ---

    /**
     * Create-time validation. The ADMIN-role prohibition is enforced FIRST: assigning the
     * ADMIN role via the CRUD API is never permitted (12.1). The check runs regardless of
     * caller role and independent of any frontend, because the framework invokes this hook
     * for every create (12.3, 12.4).
     *
     * <p>The CLIENT-role prohibition is enforced next as defense in depth (FOR-03-05 Requirement
     * 10.11): a {@code roleId} resolving to code {@code CLIENT} is rejected on the generic
     * {@code POST /api/users} create with HTTP 403, so CLIENT users can only be created through the
     * dedicated client-registration endpoint. The legitimate {@link #createClient} path sets a
     * per-thread flag that permits the server-resolved CLIENT role here.
     */
    @Override
    public void validateCreate(UserServiceExtendedModel model) {
        RoleEntity role = findRoleById(model.roleId());
        if (ADMIN_ROLE_CODE.equals(role.getCode())) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.user.admin.role.forbidden");
        }
        if (CLIENT_ROLE_CODE.equals(role.getCode()) && !CLIENT_CREATE_ALLOWED.get()) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.user.client.role.forbidden");
        }
        validateEmailUniqueness(model.email(), null);
        validateLocale(model.locale());
    }

    /**
     * Update-time validation. The ADMIN-role prohibition is enforced FIRST: promoting a
     * non-ADMIN user to ADMIN is never permitted (12.2). An unchanged role — including an
     * existing ADMIN remaining ADMIN — is allowed (12.6). Enforcement is independent of the
     * caller's role and any frontend, since the framework invokes this hook for every update
     * (12.3, 12.4).
     */
    @Override
    public void validateUpdate(UserEntity existing, UserServiceExtendedModel update) {
        RoleEntity targetRole = findRoleById(update.roleId());
        String existingRoleCode = existing.getRole() != null ? existing.getRole().getCode() : null;
        if (!ADMIN_ROLE_CODE.equals(existingRoleCode) && ADMIN_ROLE_CODE.equals(targetRole.getCode())) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.user.admin.role.forbidden");
        }
        validateEmailUniqueness(update.email(), existing.getId());
        validateLocale(update.locale());
    }

    /**
     * Overrides the framework update to evict the affected user's project-access cache entry
     * after a user update completes (Requirement 8.4). A user's role change can alter what
     * projects the user may see (e.g. ADMIN bypass), so the cached allowed-project-ids set is
     * invalidated so the next project-scoped read reloads it. Scope is limited to this touch
     * point; no FOR-03-08 controller migration is performed here.
     */
    @Override
    public UserServiceExtendedModel update(Long id, UserServiceExtendedModel model) {
        UserServiceExtendedModel result = AdminService.super.update(id, model);
        projectAccessCache.invalidate(id);
        return result;
    }

    /**
     * Post-create hook: issues exactly one invite token and dispatches the role-dependent
     * invitation email for the freshly created user (3.4, 3.7, 3.8). The user is always persisted
     * with User_Status INVITED and a null passwordHash because {@code UserServiceMapper} hard-codes
     * the status and the request DTOs expose none. Because the framework runs this hook inside the
     * transactional {@code create(...)}, a token-persist or mail failure propagates and rolls back
     * the user insert so neither the user nor the invite is retained (3.6).
     */
    @Override
    public void afterCreate(UserEntity user) {
        inviteService.issueInvite(user);
    }

    // --- Client registration (reuses the invite create path) ---

    /**
     * Creates a CLIENT user through the same framework {@code create(...)} path the admin create
     * uses, so the existing {@link #afterCreate(UserEntity)} &rarr;
     * {@link InviteService#issueInvite(UserEntity)} hook issues the invite token and dispatches the
     * client-portal invitation email (FOR-03-05 Requirements 10.4, 10.5).
     *
     * <p>The user is built as a {@link UserServiceExtendedModel} carrying the server-resolved
     * {@code clientRole} id; the {@code UserServiceMapper} resolves that id into the managed
     * {@link RoleEntity} on the created entity. Because the service-model exposes neither status nor
     * password, the persisted user keeps the {@link UserEntity} defaults — {@code status = INVITED}
     * and a {@code null} password hash — exactly like the invite flow (Requirement 10.4). Running
     * inside the transactional framework {@code create(...)}, an invite-token or mail failure rolls
     * back the whole create.
     *
     * <p>The supplied {@code clientRole} is expected to be the seeded {@code CLIENT} role resolved by
     * the caller ({@code ClientRegistrationService} via {@code RoleDao.findByCode("CLIENT")}); this
     * method does not itself resolve or restrict the role, it only fixes it on the created user.
     *
     * @param name       the client's display name
     * @param email      the client's email address (uniqueness enforced by {@link #validateCreate})
     * @param phone      the client's optional phone number
     * @param locale     the client's optional locale
     * @param clientRole the server-resolved CLIENT role to assign to the created user
     * @return the persisted, INVITED CLIENT {@link UserEntity}
     */
    public UserEntity createClient(String name, String email, String phone, String locale, RoleEntity clientRole) {
        // locale is optional (Req 10.1); the users.locale column is NOT NULL with a "ru" default,
        // so fall back to "ru" when the caller omits it or supplies a blank value.
        String resolvedLocale = (locale == null || locale.isBlank()) ? "ru" : locale;
        UserServiceExtendedModel model = new UserServiceExtendedModel(
                null,
                name,
                email,
                phone,
                clientRole.getId(),
                clientRole.getNameRU(),
                true,
                resolvedLocale,
                null
        );
        UserServiceExtendedModel created;
        CLIENT_CREATE_ALLOWED.set(Boolean.TRUE);
        try {
            created = create(model);
        } finally {
            CLIENT_CREATE_ALLOWED.remove();
        }
        return dao.findById(created.id())
                .orElseThrow(() -> new ForemenApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error.entity.not.found", created.id()));
    }

    // --- Worker record creation (uninvited WORKER, no password, no email) ---

    /**
     * FOR-05-09 (task 14.2, Requirement 13 criterion 1) — creates an <b>uninvited</b> WORKER user
     * for the {@code Worker_Record_Flow}, distinct from both the generic {@code POST /api/users}
     * create and the {@link #createClient} path: the user is persisted with a {@code null} password
     * so it cannot authenticate, status {@link UserStatus#INVITED} that reflects "not invited" (no
     * invite token is issued and <b>no invitation email is sent</b>), and {@code active = true}
     * (Requirement 13 criterion 1, D-new). The role is the caller-resolved WORKER role, fixed
     * server-side; any role / status / active flag from the request is ignored by the caller before
     * this method runs.
     *
     * <p>Unlike {@link #createClient}, this does <b>not</b> go through the framework
     * {@code create(...)} path, because that path's {@link #afterCreate(UserEntity)} hook issues an
     * invite token and dispatches an invitation email — exactly what an uninvited worker record must
     * not do. Instead the {@link UserEntity} is built and saved directly (mirroring the bespoke
     * {@link #deleteById} write), carrying the new nullable worker attributes
     * ({@code workerKind}/{@code contactPerson}/{@code nip}, D8) that the generic service model does
     * not express, and a single {@code CREATE} audit row is written. The whole write runs inside the
     * class-level transaction, so a failure here rolls back with the enclosing
     * {@code WorkerRecordService} flow (Requirement 13 criterion 12).
     *
     * <p><b>Duplicate email (Requirement 13 criterion 7).</b> A supplied email is checked
     * case-insensitively against every existing user; a match is rejected with the same 409
     * {@code error.user.email.already.exists} the client-registration flow surfaces. A {@code null}
     * email (an uninvited worker record without an email) skips the check and is never a duplicate.
     * Under a race the application check may pass for two concurrent inserts; the {@code users.email}
     * unique constraint then admits at most one and the loser's
     * {@link org.springframework.dao.DataIntegrityViolationException} is translated to the same 409,
     * so exactly one worker is committed (Requirement 13 criterion 7).
     *
     * @param name          the display name (trimmed person or company name)
     * @param email         the stored email, or {@code null} for an uninvited worker without one
     * @param phone         the stored phone, or {@code null}
     * @param workerRole    the server-resolved WORKER role
     * @param workerKind    {@code PERSON} / {@code COMPANY} (D8)
     * @param contactPerson the stored contact person ({@code COMPANY} only), or {@code null}
     * @param nip           the normalized NIP ({@code COMPANY} only), or {@code null}
     * @return the persisted, uninvited WORKER {@link UserEntity}
     * @throws ForemenApiException 409 {@code error.user.email.already.exists} on a duplicate email
     */
    public UserEntity createWorkerRecord(String name,
                                         String email,
                                         String phone,
                                         RoleEntity workerRole,
                                         WorkerKind workerKind,
                                         String contactPerson,
                                         String nip) {
        // Req 13.7 — case-insensitive duplicate-email guard. A null email is never a duplicate.
        if (email != null) {
            dao.findByEmailIgnoreCase(email).ifPresent(existing -> {
                throw new ForemenApiException(
                        HttpStatus.CONFLICT, "error.user.email.already.exists", email);
            });
        }

        UserEntity user = new UserEntity();
        user.setName(name);
        user.setEmail(email);
        user.setPhone(phone);
        user.setRole(workerRole);
        user.setActive(true);                 // Req 13.1 — active = true
        user.setPasswordHash(null);           // Req 13.1 — no password, cannot authenticate
        user.setStatus(UserStatus.INVITED);   // Req 13.1 — "not invited": no token issued, no email
        user.setLocale("ru");                 // users.locale is NOT NULL; the worker form carries none
        user.setWorkerKind(workerKind);       // D8 — PERSON / COMPANY
        user.setContactPerson(contactPerson); // D8 — COMPANY only, else null
        user.setNip(nip);                     // D8 — COMPANY only, else null

        UserEntity saved;
        try {
            saved = dao.save(user);
            entityManager.flush();            // surface a unique-constraint race as a 409 here (Req 13.7)
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ForemenApiException(
                    HttpStatus.CONFLICT, "error.user.email.already.exists", email);
        }

        // Exactly one CREATE audit row for the new worker user, mirroring the generic create audit.
        saveAudit(null, saved, "CREATE");
        return saved;
    }

    // --- Soft-delete (set active = false) ---

    @Override
    public void deleteById(Long id) {
        UserEntity user = dao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id));

        UserEntity before = new UserEntity();
        before.setId(user.getId());
        before.setActive(user.isActive());

        user.setActive(false);
        dao.save(user);
        entityManager.flush();
        saveAudit(before, user, "DEACTIVATE");
    }

    // --- Validation helpers ---

    private void validateEmailUniqueness(String email, Long excludeId) {
        if (email == null) return;
        dao.findByEmail(email).ifPresent(existing -> {
            if (!existing.getId().equals(excludeId)) {
                throw new ForemenApiException(HttpStatus.CONFLICT, "error.user.email.already.exists", email);
            }
        });
    }

    private void validateLocale(String locale) {
        if (locale != null && !SUPPORTED_LOCALES.contains(locale)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.locale.invalid", locale);
        }
    }

    /**
     * Resolves a role by id and enforces the ADMIN-role prohibition as a hard CONFLICT guard
     * (BUG 1.6, Requirements 2.6, 3.3). Assigning the ADMIN role through the user-management API
     * is never permitted, so a resolved role whose code is {@link #ADMIN_ROLE_CODE} is rejected
     * with HTTP 409 and error key {@code error.user.admin.role.prohibited}. Non-ADMIN roles are
     * returned unchanged.
     *
     * <p>The create/update validation hooks intentionally do NOT route through this guard: they
     * use {@link #findRoleById(Long)} instead, because they apply their own ADMIN semantics —
     * a FORBIDDEN (403) prohibition on create/promotion with the {@code error.user.admin.role.forbidden}
     * key, while still allowing an existing ADMIN to remain ADMIN on an unchanged-role update
     * (Requirement 12.6). Keeping this CONFLICT guard separate lets both semantics coexist without
     * one overriding the other.
     *
     * <p>This method is retained as the single, directly-testable CONFLICT enforcement point for
     * the ADMIN-role prohibition (exercised by the FOR-02-07 bug-condition and preservation tests);
     * it is intentionally not invoked from the hooks above.
     */
    @SuppressWarnings("unused")
    private RoleEntity resolveRole(Long roleId) {
        RoleEntity role = findRoleById(roleId);
        if (ADMIN_ROLE_CODE.equals(role.getCode())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.user.admin.role.prohibited");
        }
        return role;
    }

    /**
     * Looks up a role by id without any ADMIN-role restriction: validates that an id was supplied
     * and that the role exists. Used by the create/update validation hooks, which apply their own
     * ADMIN-role rules (see {@link #validateCreate}/{@link #validateUpdate}) rather than the CONFLICT
     * guard in {@link #resolveRole(Long)}.
     */
    private RoleEntity findRoleById(Long roleId) {
        if (roleId == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.role.required");
        }
        return roleDao.findById(roleId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.role.not.found", roleId));
    }
}
