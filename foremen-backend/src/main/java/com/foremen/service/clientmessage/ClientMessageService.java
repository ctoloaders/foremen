package com.foremen.service.clientmessage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.persistence.LocaleAttributeConverter;
import com.foremen.dao.AdminDao;
import com.foremen.dao.ClientMessageDao;
import com.foremen.dao.ClientMessageTranslationDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AuthorSide;
import com.foremen.dao.model.ClientMessageEntity;
import com.foremen.dao.model.ClientMessageTranslationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.ClientMessageServiceExtendedModel;
import com.foremen.service.model.ClientMessageServiceModel;
import com.foremen.service.model.mapper.ClientMessageServiceMapper;
import com.foremen.service.clientmessage.mapper.ClientMessageDtoMapper;
import com.foremen.service.translation.MessageTranslationService;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-12 (Requirements 2, 3, 5, 6, 8.5, 13.1; design §ClientMessageService) — the lifecycle
 * service behind the per-project client↔staff conversation. It owns a single ordered message thread
 * per project: the CLIENT asks questions and the MANAGER replies, each message auto-translated into
 * the supported locales (PL/RU) and optionally carrying media.
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@link com.foremen.service.WorkReportService}
 * / {@link com.foremen.service.payroll.WorkerPayoutService}), it implements exactly one CRUD contract
 * — {@link ProjectScopedService} — supplies the standard CRUD plumbing ({@link #getDao()},
 * {@link #getMapper()}, {@link #getEntityManager()}, {@link #getDaoModelClass()},
 * {@link #getAuditLogDao()}), the single mandatory per-entity override {@link #getProjectIdPath()}
 * &rarr; {@code "project.id"} (entity-creation-rules step 4; R8.2), and wires
 * {@link #allowedProjectIds(Long)} to {@link ProjectAccessCache}. Every inherited LIST read is
 * project-filtered and every by-id read/mutation runs the per-id project-access guard.
 *
 * <p>Beyond the generic CRUD it owns the message lifecycle:
 * <ul>
 *   <li>{@link #post} — derive {@code authorSide} from the author's project role (R2.1), validate a
 *       non-empty body (R2.2) on an {@code ACTIVE} project (R2.4), enforce MANAGER-only reply (R3),
 *       detect the original locale + persist the original mirror and best-effort machine
 *       translations (R6), attach media (R4), write audit (R13.1), and publish the notification
 *       event (R7);</li>
 *   <li>{@link #listThread} — the per-reader-locale thread projection + per-reader unread count
 *       (R5.4, R6.4);</li>
 *   <li>{@link #markRead} — MANAGER-only, first-read-wins marking of a {@code CLIENT}-side message
 *       (R5, R3.3).</li>
 * </ul>
 *
 * <p>The MANAGER-only reply/mark-read rule and the CLIENT-side-only authoring side are enforced here
 * <strong>on top of</strong> the ABAC grant (R3.4, R8.5): a FOREMAN with READ can never reply or
 * mark read even though the resource is visible to them.
 */
@Service
@Transactional
public class ClientMessageService
        implements ProjectScopedService<ClientMessageServiceModel, ClientMessageServiceExtendedModel,
        ClientMessageEntity, Long> {

    private static final Logger log = LoggerFactory.getLogger(ClientMessageService.class);

    // --- Project Company_Role codes ---
    static final String CLIENT_ROLE_CODE = "CLIENT";
    static final String MANAGER_ROLE_CODE = "MANAGER";
    static final String ADMIN_ROLE_CODE = "ADMIN";

    // --- Message codes (bundle wiring is task 11.1) ---
    static final String NOT_FOUND_MESSAGE = "error.entity.not.found";
    static final String EMPTY_MESSAGE = "error.clientMessage.empty";
    static final String PROJECT_NOT_ACTIVE = "error.clientMessage.project.notActive";
    static final String REPLY_NOT_MANAGER = "error.clientMessage.reply.notManager";
    static final String READ_NOT_MANAGER = "error.clientMessage.read.notManager";

    private final ClientMessageDao clientMessageDao;
    private final ClientMessageTranslationDao translationDao;
    private final ProjectMemberDao projectMemberDao;
    private final ProjectAccessCache projectAccessCache;
    private final ClientMessageMediaService mediaService;
    private final MessageTranslationService translationService;
    private final ClientMessageDtoMapper dtoMapper;
    private final ClientMessageServiceMapper serviceMapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    public ClientMessageService(ClientMessageDao clientMessageDao,
                                ClientMessageTranslationDao translationDao,
                                ProjectMemberDao projectMemberDao,
                                ProjectAccessCache projectAccessCache,
                                ClientMessageMediaService mediaService,
                                MessageTranslationService translationService,
                                ClientMessageDtoMapper dtoMapper,
                                ClientMessageServiceMapper serviceMapper,
                                AuditLogDao auditLogDao,
                                EntityManager entityManager,
                                ApplicationEventPublisher eventPublisher) {
        this.clientMessageDao = clientMessageDao;
        this.translationDao = translationDao;
        this.projectMemberDao = projectMemberDao;
        this.projectAccessCache = projectAccessCache;
        this.mediaService = mediaService;
        this.translationService = translationService;
        this.dtoMapper = dtoMapper;
        this.serviceMapper = serviceMapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<ClientMessageEntity, Long> getDao() {
        return clientMessageDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<ClientMessageEntity, ClientMessageServiceModel,
            ClientMessageServiceExtendedModel> getMapper() {
        return serviceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<ClientMessageEntity> getDaoModelClass() {
        return ClientMessageEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /** The single mandatory per-entity override (entity-creation-rules step 4; R8.2). */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Post (R2, R3, R4, R6, R7, R13.1) ------------------------------------------------------

    /**
     * Posts a message into the project's thread, assembling and persisting the whole effect (the
     * message row, the original-locale mirror + best-effort machine translations, the attached media,
     * the audit row, and the published notification event) in one transaction:
     *
     * <ol>
     *   <li>enforce project scope ({@link #assertProjectRead(Long)}) and resolve the authenticated
     *       author + their project {@code Company_Role}; derive {@code authorSide} (CLIENT role →
     *       {@link AuthorSide#CLIENT}, otherwise {@link AuthorSide#STAFF}) — never from the body
     *       (R2.1);</li>
     *   <li>a {@code STAFF} post requires the author to be a MANAGER (or ADMIN); a FOREMAN /
     *       other-staff reply is rejected 403 {@link #REPLY_NOT_MANAGER} (R3.1, R3.2, R3.4);</li>
     *   <li>reject a blank body with no media 400 {@link #EMPTY_MESSAGE} (R2.2), and a post on a
     *       non-{@code ACTIVE} project 409 {@link #PROJECT_NOT_ACTIVE} (R2.4, R8.1);</li>
     *   <li>detect the original locale via {@link MessageTranslationService#detectLocale} when the
     *       request carries none (R6.1), persist the message + the original-locale mirror
     *       ({@code machine=false}, R2.3), then for every other supported locale call
     *       {@link MessageTranslationService#translate} best-effort — a failure logs and skips that
     *       locale, never throws (R6.2, R6.3);</li>
     *   <li>attach the validated media via {@link ClientMessageMediaService#attachAll} (R4);</li>
     *   <li>write audit (R13.1) and publish a {@link ClientMessageNotificationEvent} the
     *       after-commit emitter (task 7.1) consumes (R7).</li>
     * </ol>
     *
     * @param projectId the owning project id (project scope is enforced here)
     * @param req       the post payload (text + optional explicit locale); {@code authorSide} is
     *                  never read from it (R2.1)
     * @param media     the optional uploaded media files (may be {@code null}/empty)
     * @return the persisted message projected for the author's own locale
     */
    @Transactional
    public ClientMessageDto post(Long projectId, PostMessageRequest req, List<MultipartFile> media) {
        assertProjectRead(projectId);

        UserEntity author = requireAuthor();
        String authorRole = projectRoleCode(author.getId(), projectId);
        AuthorSide authorSide = CLIENT_ROLE_CODE.equals(authorRole) ? AuthorSide.CLIENT : AuthorSide.STAFF;

        // R3 — a STAFF post is a reply and is reserved to the MANAGER; ADMIN bypasses either by the
        // project-role code or the global security ADMIN authority (consistent with markRead).
        if (authorSide == AuthorSide.STAFF && !isManagerOrAdmin(authorRole) && !isCallerAdmin()) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, REPLY_NOT_MANAGER);
        }

        String text = req == null ? null : req.text();
        boolean blankText = text == null || text.isBlank();
        boolean noMedia = media == null || media.isEmpty();
        // R2.2 — blank text AND no media is nothing to post.
        if (blankText && noMedia) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, EMPTY_MESSAGE);
        }

        // R2.4 / R8.1 — the thread is read-only off the execution stage: a post needs an ACTIVE project.
        ProjectEntity project = entityManager.find(ProjectEntity.class, projectId);
        if (project == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, projectId);
        }
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ForemenApiException(HttpStatus.CONFLICT, PROJECT_NOT_ACTIVE);
        }

        // R6.1 — detect the original locale: an explicit supported request locale wins, else the seam
        // classifies from the text + the author's preferred locale (PL fallback).
        Locale authorPreferred = author.getLocale();
        Locale requestLocale = req == null ? null : req.locale();
        Locale originalLocale = LocaleAttributeConverter.isSupported(requestLocale)
                ? requestLocale
                : translationService.detectLocale(text == null ? "" : text, authorPreferred);

        String originalText = text == null ? "" : text;

        ClientMessageEntity message = new ClientMessageEntity();
        message.setProject(entityManager.getReference(ProjectEntity.class, projectId));
        message.setAuthor(author);
        message.setAuthorSide(authorSide);
        message.setOriginalText(originalText);
        message.setOriginalLocale(originalLocale);
        // R2.5 — readAt/readBy start null; only a CLIENT-side message ever carries unread state.

        ClientMessageEntity saved = clientMessageDao.save(message);
        entityManager.flush();

        // R2.3 — the original-locale mirror (machine=false).
        persistTranslation(saved, originalLocale, originalText, false);
        // R6.2 / R6.3 — best-effort machine translation into every OTHER supported locale.
        translateIntoOtherLocales(saved, originalLocale, originalText);

        // R4 — attach the validated media as part of the post transaction; the uploader is the author.
        mediaService.attachAll(saved, media, author);

        // R13.1 — audit the post via the shared audit mechanism.
        saveAudit(null, saved, "CREATE");

        // R7 — publish the after-commit notification event (recipients resolved by the emitter).
        eventPublisher.publishEvent(
                new ClientMessageNotificationEvent(projectId, saved.getId(), authorSide));

        // Project the just-posted message for the author's own locale.
        return dtoMapper.toDto(saved, readerLocale(authorPreferred), mediaService.signedUrls(saved));
    }

    // --- List thread (R5.4, R6.4) --------------------------------------------------------------

    /**
     * The project's whole thread projected for one reader locale (R6.4): each message carries its
     * reader-locale text (the translation row for the reader locale, else {@code originalText}), the
     * original text + locale for the "show original" quick-view, the read state, and the per-media
     * signed download URLs. Also computes the per-reader unread count (R5.4): for a MANAGER reader
     * the number of {@code CLIENT}-side messages not yet read; for a non-MANAGER reader zero (only a
     * MANAGER participates in the read marker, R5.3). Project scope is enforced before the read.
     *
     * @param projectId    the owning project id (must be in the caller's accessible set unless ADMIN)
     * @param readerLocale the reader's locale used to select each message's text (PL fallback)
     * @return the ordered, reader-localized {@link ThreadView}
     */
    @Transactional(readOnly = true)
    public ThreadView listThread(Long projectId, Locale readerLocale) {
        assertProjectRead(projectId);

        List<ClientMessageEntity> messages = clientMessageDao.findThreadByProjectId(projectId);

        long unreadCount = isCallerManagerOnProject(projectId)
                ? messages.stream()
                        .filter(m -> m.getAuthorSide() == AuthorSide.CLIENT && m.getReadAt() == null)
                        .count()
                : 0L;

        return dtoMapper.toThreadView(messages, readerLocale(readerLocale),
                mediaService.signedUrls(messages), unreadCount);
    }

    // --- Mark read (R5, R3.3) ------------------------------------------------------------------

    /**
     * Marks a {@code CLIENT}-side message read on the MANAGER's first open (R5.1), first-read-wins:
     * sets {@code readAt}/{@code readBy} only when {@code readAt} is still null AND the message is
     * {@code CLIENT}-side; an already-read message or a {@code STAFF}-side message is a no-op
     * returning the current state (R5.2, R5.3). MANAGER-only: a non-MANAGER (non-ADMIN) is rejected
     * 403 {@link #READ_NOT_MANAGER} (R3.3, R3.4). Project scope is enforced first via the inherited
     * by-id access guard.
     *
     * @param messageId the message to mark read
     * @return the (possibly updated) message projected for the caller's own locale
     */
    @Transactional
    public ClientMessageDto markRead(Long messageId) {
        assertProjectAccess(messageId);
        ClientMessageEntity message = clientMessageDao.findById(messageId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, messageId));

        Long projectId = message.getProject() == null ? null : message.getProject().getId();
        // R3.3 — only a MANAGER (or ADMIN) may mark a client message read.
        if (!isCallerManagerOnProject(projectId)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, READ_NOT_MANAGER);
        }

        // R5.1 / R5.2 / R5.3 — first-read-wins on a CLIENT-side message; otherwise a no-op.
        if (message.getAuthorSide() == AuthorSide.CLIENT && message.getReadAt() == null) {
            String before = serializeEntity(message);
            message.setReadAt(OffsetDateTime.now());
            message.setReadBy(currentCaller());
            ClientMessageEntity saved = clientMessageDao.save(message);
            entityManager.flush();
            saveAuditWithSnapshot(before, saved, "UPDATE");
            message = saved;
        }

        return dtoMapper.toDto(message, readerLocale(callerPreferredLocale()),
                mediaService.signedUrls(message));
    }

    // --- Translation helpers (R6) --------------------------------------------------------------

    /** Persist one per-locale translation row (the original mirror or a machine translation). */
    private void persistTranslation(ClientMessageEntity message, Locale locale, String text, boolean machine) {
        ClientMessageTranslationEntity translation = new ClientMessageTranslationEntity();
        translation.setMessage(message);
        translation.setLocale(locale);
        translation.setText(text);
        translation.setMachine(machine);
        translationDao.save(translation);
    }

    /**
     * R6.2 / R6.3 — for every supported locale OTHER than the original, obtain a machine translation
     * best-effort and persist it ({@code machine=true}); a failure or an empty result leaves that
     * locale untranslated (original-only) and never blocks the post.
     */
    private void translateIntoOtherLocales(ClientMessageEntity message, Locale originalLocale, String originalText) {
        String originalLanguage = originalLocale == null ? null : originalLocale.getLanguage();
        for (Locale target : LocaleAttributeConverter.SUPPORTED) {
            if (originalLanguage != null && originalLanguage.equals(target.getLanguage())) {
                continue; // the original-locale row is the machine=false mirror.
            }
            try {
                Optional<String> translated = translationService.translate(originalText, originalLocale, target);
                translated.ifPresent(value -> persistTranslation(message, target, value, true));
            } catch (RuntimeException e) {
                // R6.3 — never surface a translation failure; log and skip the locale.
                log.warn("Translation into {} failed for client message {}; leaving it untranslated",
                        target, message.getId(), e);
            }
        }
    }

    // --- Validation / scope helpers ------------------------------------------------------------

    /**
     * Project-scope READ guard for the project-id-level reads ({@link #post} / {@link #listThread}):
     * ADMIN bypasses; otherwise the {@code projectId} must be in the caller's accessible project set.
     * Denied uniformly with a 404 so an out-of-scope project is not revealed, consistent with
     * {@link ProjectScopedService#assertProjectAccess}.
     */
    private void assertProjectRead(Long projectId) {
        if (isCallerAdmin()) {
            return;
        }
        Long callerId = callerUserId();
        if (callerId == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, projectId);
        }
        Set<Long> allowed = allowedProjectIds(callerId);
        if (allowed == null || projectId == null || !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, projectId);
        }
    }

    /** True when the caller is an ADMIN, or a MANAGER project member of {@code projectId} (R3). */
    private boolean isCallerManagerOnProject(Long projectId) {
        if (isCallerAdmin()) {
            return true;
        }
        return MANAGER_ROLE_CODE.equals(projectRoleCode(callerUserId(), projectId));
    }

    private static boolean isManagerOrAdmin(String roleCode) {
        return MANAGER_ROLE_CODE.equals(roleCode) || ADMIN_ROLE_CODE.equals(roleCode);
    }

    // --- Caller / role resolution (mirrors WorkReportService) ----------------------------------

    /** The authenticated author as a managed reference; 401 when the caller cannot be resolved. */
    private UserEntity requireAuthor() {
        Long callerId = callerUserId();
        if (callerId == null) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, NOT_FOUND_MESSAGE);
        }
        return entityManager.getReference(UserEntity.class, callerId);
    }

    /** The current caller as a managed {@link UserEntity} reference, or {@code null} if unresolved. */
    private UserEntity currentCaller() {
        Long callerId = callerUserId();
        return callerId == null ? null : entityManager.getReference(UserEntity.class, callerId);
    }

    /** The caller's preferred locale (their {@code UserEntity.locale}), or {@code null} if unresolved. */
    private Locale callerPreferredLocale() {
        Long callerId = callerUserId();
        if (callerId == null) {
            return null;
        }
        UserEntity caller = entityManager.find(UserEntity.class, callerId);
        return caller == null ? null : caller.getLocale();
    }

    /** The caller's project {@code Company_Role} code for {@code projectId}, or {@code null}. */
    private String projectRoleCode(Long userId, Long projectId) {
        if (userId == null || projectId == null) {
            return null;
        }
        ProjectMemberEntity member =
                projectMemberDao.findByUserIdAndProjectId(userId, projectId).orElse(null);
        if (member == null || member.getProjectRole() == null) {
            return null;
        }
        return member.getProjectRole().getCode();
    }

    /** Resolve the reader locale, falling back to PL when {@code null}/unsupported. */
    private static Locale readerLocale(Locale locale) {
        return LocaleAttributeConverter.isSupported(locale) ? locale : LocaleAttributeConverter.FALLBACK;
    }

    /** True iff the authenticated caller carries the exact ADMIN authority. */
    private static boolean isCallerAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (("ROLE_" + ADMIN_ROLE_CODE).equals(a) || ADMIN_ROLE_CODE.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /** The authenticated caller's numeric user id, or {@code null} when it cannot be resolved. */
    private static Long callerUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
